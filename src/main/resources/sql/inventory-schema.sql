-- ============================================================
-- 库存系统表结构（MySQL 8.0 + InnoDB）
-- ============================================================

-- 仓库层：仓库库存表
CREATE TABLE IF NOT EXISTS `warehouse_inventory` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `warehouse_id` BIGINT NOT NULL COMMENT '仓库ID',
  `product_id` BIGINT NOT NULL COMMENT '商品ID',
  `total_stock` INT NOT NULL DEFAULT 0 COMMENT '总库存',
  `available_stock` INT NOT NULL DEFAULT 0 COMMENT '可用库存',
  `locked_stock` INT NOT NULL DEFAULT 0 COMMENT '锁定库存',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_warehouse_product` (`warehouse_id`, `product_id`),
  KEY `idx_product_id` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库库存表';

-- 调度层：预占记录表
CREATE TABLE IF NOT EXISTS `reservation_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `reservation_id` VARCHAR(64) NOT NULL COMMENT '预占ID',
  `order_id` VARCHAR(64) NOT NULL COMMENT '订单ID',
  `product_id` BIGINT NOT NULL COMMENT '商品ID',
  `quantity` INT NOT NULL COMMENT '预占数量',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0-预占中，1-已锁定，2-已解锁，3-已确认',
  `expire_time` DATETIME COMMENT '过期时间',
  `payment_id` VARCHAR(64) COMMENT '支付流水ID',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reservation_id` (`reservation_id`),
  KEY `idx_order_id` (`order_id`),
  KEY `idx_product_id` (`product_id`),
  KEY `idx_order_product` (`order_id`, `product_id`),
  KEY `idx_status_expire` (`status`, `expire_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预占记录表';

-- 销售层：可销售库存表
CREATE TABLE IF NOT EXISTS `sales_inventory` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `product_id` BIGINT NOT NULL COMMENT '商品ID',
  `available_stock` INT NOT NULL DEFAULT 0 COMMENT '可销售库存',
  `allocated_stock` INT NOT NULL DEFAULT 0 COMMENT '已分配库存',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_id` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可销售库存表';

-- 仓库层：库存流水表
CREATE TABLE IF NOT EXISTS `inventory_movement` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `movement_id` VARCHAR(64) NOT NULL COMMENT '流水号',
  `warehouse_id` BIGINT NOT NULL COMMENT '仓库ID',
  `product_id` BIGINT NOT NULL COMMENT '商品ID',
  `movement_type` VARCHAR(32) NOT NULL COMMENT '类型：INBOUND-入库，OUTBOUND-出库',
  `quantity` INT NOT NULL COMMENT '数量',
  `reference_id` VARCHAR(64) COMMENT '关联单号',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_movement_id` (`movement_id`),
  KEY `idx_warehouse_product` (`warehouse_id`, `product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存流水表';
