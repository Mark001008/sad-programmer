package com.sad.programmer.business.inventory.sales;

import com.sad.programmer.database.common.JdbcUtil;
import com.sad.programmer.redis.common.RedisUtil;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 销售层库存服务实现类（Redis 缓存 + MySQL 持久化投产版本）。
 *
 * <p>采用 Redis 缓存 + MySQL 持久化的双层架构，兼顾高并发读写性能和数据持久性：
 * <ul>
 *   <li>高并发读：优先读 Redis 缓存，缓存未命中时查 MySQL 并回写缓存</li>
 *   <li>高并发写（扣减）：Redis Lua 脚本原子扣减，成功后同步更新 MySQL</li>
 *   <li>渠道库存：V1.0 不做渠道隔离，所有渠道共享总可用库存</li>
 * </ul></p>
 *
 * <p>数据一致性保障：
 * <ul>
 *   <li>Lua 脚本保证 Redis 扣减的原子性</li>
 *   <li>MySQL 使用乐观锁（available_stock &gt;= quantity）防止超卖</li>
 *   <li>MySQL 扣减失败时通过 INCRBY 补偿回滚 Redis</li>
 * </ul></p>
 *
 * @author sad-programmer
 */
public class SalesInventoryServiceImpl implements SalesInventoryService {

    /**
     * 默认渠道名称，当 channel 参数为 null 或空字符串时使用。
     */
    private static final String DEFAULT_CHANNEL = "DEFAULT";

    /**
     * Redis 缓存过期时间（秒）。
     */
    private static final int CACHE_TTL_SECONDS = 3600;

    /**
     * Lua 脚本：原子扣减 Redis 库存。
     *
     * <p>返回值：
     * <ul>
     *   <li>-1 — Key 不存在，需从 DB 加载</li>
     *   <li>0  — 库存不足</li>
     *   <li>1  — 扣减成功</li>
     * </ul></p>
     */
    private static final String DEDUCT_LUA_SCRIPT =
            "local stock = tonumber(redis.call('GET', KEYS[1]))\n"
                    + "if stock == nil then\n"
                    + "    return -1\n"
                    + "end\n"
                    + "if stock < tonumber(ARGV[1]) then\n"
                    + "    return 0\n"
                    + "end\n"
                    + "redis.call('DECRBY', KEYS[1], ARGV[1])\n"
                    + "return 1";

    /**
     * 最大重试次数：当 Redis Key 不存在时，从 DB 加载后重试的次数上限。
     */
    private static final int MAX_RETRY_COUNT = 1;

    /**
     * 销售库存数据访问对象。
     */
    private final SalesInventoryDao dao;

    /**
     * 构造销售层库存服务实现。
     *
     * @param dao 销售库存数据访问对象，不能为 null
     * @throws IllegalArgumentException 当 dao 为 null 时抛出
     */
    public SalesInventoryServiceImpl(final SalesInventoryDao dao) {
        if (dao == null) {
            throw new IllegalArgumentException("SalesInventoryDao 不能为 null");
        }
        this.dao = dao;
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现逻辑：
     * <ol>
     *   <li>获取 MySQL 连接，查询商品库存记录</li>
     *   <li>若记录不存在则插入初始记录</li>
     *   <li>若记录存在则更新可用库存为指定数量</li>
     *   <li>将库存写入 Redis，设置 TTL 3600 秒</li>
     * </ol></p>
     */
    @Override
    public void syncActualStock(final long productId, final int quantity) {
        if (productId <= 0) {
            throw new IllegalArgumentException("商品ID必须大于0，当前值：" + productId);
        }
        if (quantity < 0) {
            throw new IllegalArgumentException("实际库存数量不能为负数，当前值：" + quantity);
        }

        Connection conn = null;
        try {
            conn = JdbcUtil.getConnection(Connection.TRANSACTION_REPEATABLE_READ);
            final SalesInventoryResult existing = dao.findByProductId(conn, productId);
            if (existing == null) {
                dao.insert(conn, productId, quantity);
            } else {
                final int delta = quantity - existing.getAvailableStock();
                if (delta != 0) {
                    dao.updateAvailableStock(conn, productId, delta);
                }
            }

            // 同步写入 Redis 缓存，设置 TTL
            Jedis jedis = null;
            try {
                jedis = RedisUtil.getResource();
                jedis.set(SalesInventoryCacheKey.salesStockKey(productId),
                        String.valueOf(quantity),
                        SetParams.setParams().ex(CACHE_TTL_SECONDS));
            } finally {
                if (jedis != null) {
                    RedisUtil.returnResource(jedis);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("同步库存失败: " + e.getMessage(), e);
        } finally {
            JdbcUtil.close(conn);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现逻辑：优先读 Redis 缓存 → 缓存未命中查 MySQL 并回写缓存 → 构建结果。</p>
     */
    @Override
    public SalesInventoryResult queryAvailableStock(final long productId, final String channel) {
        if (productId <= 0) {
            throw new IllegalArgumentException("商品ID必须大于0，当前值：" + productId);
        }
        if (channel == null || channel.trim().isEmpty()) {
            throw new IllegalArgumentException("渠道名称不能为空");
        }

        final String normalizedChannel = normalizeChannel(channel);

        // 优先读 Redis 缓存
        Jedis jedis = null;
        try {
            jedis = RedisUtil.getResource();
            final String cached = jedis.get(SalesInventoryCacheKey.salesStockKey(productId));
            if (cached != null) {
                final int stock = Integer.parseInt(cached);
                return buildResult(productId, stock, normalizedChannel);
            }
        } catch (NumberFormatException e) {
            // 缓存数据异常，走 DB 回源
        } finally {
            if (jedis != null) {
                RedisUtil.returnResource(jedis);
            }
        }

        // 缓存未命中，查 MySQL 并回写
        Connection conn = null;
        try {
            conn = JdbcUtil.getConnection();
            final SalesInventoryResult dbResult = dao.findByProductId(conn, productId);
            if (dbResult == null) {
                return SalesInventoryResult.empty(productId);
            }

            // 回写 Redis 缓存
            jedis = null;
            try {
                jedis = RedisUtil.getResource();
                jedis.set(SalesInventoryCacheKey.salesStockKey(productId),
                        String.valueOf(dbResult.getAvailableStock()),
                        SetParams.setParams().ex(CACHE_TTL_SECONDS));
            } finally {
                if (jedis != null) {
                    RedisUtil.returnResource(jedis);
                }
            }

            return buildResult(productId, dbResult.getAvailableStock(), normalizedChannel);
        } catch (SQLException e) {
            throw new IllegalStateException("查询库存失败: " + e.getMessage(), e);
        } finally {
            JdbcUtil.close(conn);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>对每个商品ID执行单次查询逻辑，返回结果列表与输入列表顺序一致。</p>
     */
    @Override
    public List<SalesInventoryResult> batchQueryAvailableStock(final List<Long> productIds,
                                                                final String channel) {
        if (productIds == null || productIds.isEmpty()) {
            throw new IllegalArgumentException("商品ID列表不能为空");
        }
        if (channel == null || channel.trim().isEmpty()) {
            throw new IllegalArgumentException("渠道名称不能为空");
        }

        final List<SalesInventoryResult> results = new ArrayList<SalesInventoryResult>();
        for (final Long productId : productIds) {
            results.add(queryAvailableStock(productId, channel));
        }
        return results;
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现逻辑：
     * <ol>
     *   <li>Redis Lua 脚本原子扣减</li>
     *   <li>Lua 返回 -1：Key 不存在，从 DB 加载后重试</li>
     *   <li>Lua 返回 0：库存不足，返回 false</li>
     *   <li>Lua 返回 1：扣减成功，同步更新 MySQL</li>
     *   <li>MySQL 扣减失败时通过 INCRBY 补偿回滚 Redis</li>
     * </ol></p>
     */
    @Override
    public boolean allocateStock(final String orderId, final long productId,
                                  final int quantity, final String channel) {
        if (orderId == null || orderId.trim().isEmpty()) {
            throw new IllegalArgumentException("订单号不能为空");
        }
        if (productId <= 0) {
            throw new IllegalArgumentException("商品ID必须大于0，当前值：" + productId);
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("分配数量必须大于0，当前值：" + quantity);
        }

        final String redisKey = SalesInventoryCacheKey.salesStockKey(productId);
        int retryCount = 0;
        boolean deducted = false;

        // Redis Lua 原子扣减循环（最多重试 MAX_RETRY_COUNT 次）
        while (!deducted && retryCount <= MAX_RETRY_COUNT) {
            Jedis jedis = null;
            try {
                jedis = RedisUtil.getResource();
                final Object result = jedis.eval(DEDUCT_LUA_SCRIPT,
                        Collections.singletonList(redisKey),
                        Collections.singletonList(String.valueOf(quantity)));

                final long evalResult = (Long) result;
                if (evalResult == 1) {
                    // 扣减成功
                    deducted = true;
                } else if (evalResult == -1) {
                    // Key 不存在，从 DB 加载
                    loadStockFromDbToRedis(jedis, productId, redisKey);
                    retryCount++;
                } else {
                    // 库存不足
                    return false;
                }
            } finally {
                if (jedis != null) {
                    RedisUtil.returnResource(jedis);
                }
            }
        }

        if (!deducted) {
            return false;
        }

        // 同步更新 MySQL（乐观锁扣减）
        Connection conn = null;
        try {
            conn = JdbcUtil.getConnection(Connection.TRANSACTION_REPEATABLE_READ);
            conn.setAutoCommit(false);
            final int affected = dao.updateAvailableStockWithCheck(conn, productId, quantity);
            if (affected == 0) {
                // MySQL 扣减失败，补偿回滚 Redis
                rollbackRedis(productId, quantity);
                conn.rollback();
                return false;
            }
            conn.commit();
            return true;
        } catch (SQLException e) {
            // MySQL 异常，补偿回滚 Redis
            rollbackRedis(productId, quantity);
            throw new IllegalStateException("分配库存失败: " + e.getMessage(), e);
        } finally {
            JdbcUtil.close(conn);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现逻辑：查找商品库存记录，增加可用库存，同步更新 Redis 缓存。</p>
     */
    @Override
    public boolean reclaimStock(final String orderId, final long productId,
                                 final int quantity, final String channel) {
        if (orderId == null || orderId.trim().isEmpty()) {
            throw new IllegalArgumentException("订单号不能为空");
        }
        if (productId <= 0) {
            throw new IllegalArgumentException("商品ID必须大于0，当前值：" + productId);
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("回收数量必须大于0，当前值：" + quantity);
        }

        Connection conn = null;
        try {
            conn = JdbcUtil.getConnection(Connection.TRANSACTION_REPEATABLE_READ);
            conn.setAutoCommit(false);
            final SalesInventoryResult existing = dao.findByProductId(conn, productId);
            if (existing == null) {
                conn.rollback();
                return false;
            }
            dao.updateAvailableStock(conn, productId, quantity);
            conn.commit();

            // 同步更新 Redis 缓存
            Jedis jedis = null;
            try {
                jedis = RedisUtil.getResource();
                jedis.incrBy(SalesInventoryCacheKey.salesStockKey(productId), quantity);
            } finally {
                if (jedis != null) {
                    RedisUtil.returnResource(jedis);
                }
            }

            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("回收库存失败: " + e.getMessage(), e);
        } finally {
            JdbcUtil.close(conn);
        }
    }

    /**
     * 从 MySQL 加载商品库存并写入 Redis。
     *
     * <p>当 Redis 缓存未命中时调用，将数据库中的最新库存写入 Redis 并设置 TTL。</p>
     *
     * @param jedis     Redis 连接
     * @param productId 商品ID
     * @param redisKey  Redis Key
     */
    private void loadStockFromDbToRedis(final Jedis jedis, final long productId,
                                         final String redisKey) {
        Connection conn = null;
        try {
            conn = JdbcUtil.getConnection();
            final SalesInventoryResult dbResult = dao.findByProductId(conn, productId);
            if (dbResult != null) {
                jedis.set(redisKey, String.valueOf(dbResult.getAvailableStock()),
                        SetParams.setParams().ex(CACHE_TTL_SECONDS));
            } else {
                // 商品不存在，写入 0 防止缓存穿透
                jedis.set(redisKey, "0", SetParams.setParams().ex(CACHE_TTL_SECONDS));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("从DB加载库存失败: " + e.getMessage(), e);
        } finally {
            JdbcUtil.close(conn);
        }
    }

    /**
     * MySQL 扣减失败时补偿回滚 Redis 库存。
     *
     * @param productId 商品ID
     * @param quantity  需要回滚的数量
     */
    private void rollbackRedis(final long productId, final int quantity) {
        Jedis jedis = null;
        try {
            jedis = RedisUtil.getResource();
            jedis.incrBy(SalesInventoryCacheKey.salesStockKey(productId), quantity);
        } finally {
            if (jedis != null) {
                RedisUtil.returnResource(jedis);
            }
        }
    }

    /**
     * 构建销售库存查询结果。
     *
     * <p>V1.0 不做渠道隔离，所有渠道均显示总可用库存。</p>
     *
     * @param productId      商品ID
     * @param totalAvailable 总可用库存
     * @param channel        请求的渠道名称
     * @return 销售库存查询结果
     */
    private SalesInventoryResult buildResult(final long productId, final int totalAvailable,
                                              final String channel) {
        final Map<String, Integer> channelStock = new HashMap<String, Integer>();
        channelStock.put(DEFAULT_CHANNEL, totalAvailable);
        if (!DEFAULT_CHANNEL.equals(channel)) {
            channelStock.put(channel, totalAvailable);
        }
        return new SalesInventoryResult(productId, totalAvailable, channelStock);
    }

    /**
     * 规范化渠道名称。
     *
     * @param channel 原始渠道名称
     * @return 规范化后的渠道名称，保证不为 null 且不为空字符串
     */
    private String normalizeChannel(final String channel) {
        if (channel == null || channel.trim().isEmpty()) {
            return DEFAULT_CHANNEL;
        }
        return channel.trim();
    }
}
