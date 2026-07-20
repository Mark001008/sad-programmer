


package java.util.concurrent.atomic;
import java.util.function.IntUnaryOperator;
import java.util.function.IntBinaryOperator;
import sun.misc.Unsafe;


public class AtomicInteger extends Number implements java.io.Serializable {
    /*
     * 中文阅读提示：
     * AtomicInteger 解决的是“单个 int 变量的原子更新”问题。
     * 主线：volatile 保证可见性，Unsafe + CAS 保证更新原子性，自旋循环处理 CAS 失败重试。
     * 面试常见入口：为什么 i++ 不安全，AtomicInteger 如何修复，高竞争下为什么 LongAdder 更适合统计。
     */

    private static final long serialVersionUID = 6214790243416807050L;


    // Unsafe 提供底层 CAS 能力。普通业务代码不应该直接使用 Unsafe。
    private static final Unsafe unsafe = Unsafe.getUnsafe();
    // value 字段在 AtomicInteger 对象内的内存偏移量，CAS 时要用它定位具体字段。
    private static final long valueOffset;

    static {
        try {
            valueOffset = unsafe.objectFieldOffset
                (AtomicInteger.class.getDeclaredField("value"));
        } catch (Exception ex) { throw new Error(ex); }
    }

    // volatile 保证 value 的读写可见性。
    // 注意：volatile 自身不能保证 i++ 这种“读-改-写”复合操作的原子性。
    private volatile int value;


    public AtomicInteger(int initialValue) {
        value = initialValue;
    }


    public AtomicInteger() {
    }


    public final int get() {
        return value;
    }


    public final void set(int newValue) {
        value = newValue;
    }


    public final void lazySet(int newValue) {
        // 延迟设置，最终可见，但不要求像 volatile 写一样立刻对其他线程可见。
        // 常用于只需要发布最终状态、对即时可见性要求没那么强的场景。
        unsafe.putOrderedInt(this, valueOffset, newValue);
    }


    public final int getAndSet(int newValue) {
        return unsafe.getAndSetInt(this, valueOffset, newValue);
    }


    public final boolean compareAndSet(int expect, int update) {
        // CAS：如果当前 value 等于 expect，就原子更新成 update，并返回 true；否则返回 false。
        // 它是 AtomicInteger 原子更新能力的核心。
        return unsafe.compareAndSwapInt(this, valueOffset, expect, update);
    }


    public final boolean weakCompareAndSet(int expect, int update) {
        return unsafe.compareAndSwapInt(this, valueOffset, expect, update);
    }


    public final int getAndIncrement() {
        // 先返回旧值，再原子加 1。
        // 底层是 Unsafe.getAndAddInt，JDK 内部会通过 CAS 循环完成。
        return unsafe.getAndAddInt(this, valueOffset, 1);
    }


    public final int getAndDecrement() {
        return unsafe.getAndAddInt(this, valueOffset, -1);
    }


    public final int getAndAdd(int delta) {
        return unsafe.getAndAddInt(this, valueOffset, delta);
    }


    public final int incrementAndGet() {
        // 先原子加 1，再返回新值。
        return unsafe.getAndAddInt(this, valueOffset, 1) + 1;
    }


    public final int decrementAndGet() {
        return unsafe.getAndAddInt(this, valueOffset, -1) - 1;
    }


    public final int addAndGet(int delta) {
        return unsafe.getAndAddInt(this, valueOffset, delta) + delta;
    }


    public final int getAndUpdate(IntUnaryOperator updateFunction) {
        // 自定义更新函数版本：
        // 先读旧值，计算新值，然后 CAS 更新。
        // 如果 CAS 失败，说明期间被其他线程改过，就重新读取并再次计算。
        int prev, next;
        do {
            prev = get();
            next = updateFunction.applyAsInt(prev);
        } while (!compareAndSet(prev, next));
        return prev;
    }


    public final int updateAndGet(IntUnaryOperator updateFunction) {
        // 和 getAndUpdate 类似，只是返回值不同：这里返回更新后的新值。
        int prev, next;
        do {
            prev = get();
            next = updateFunction.applyAsInt(prev);
        } while (!compareAndSet(prev, next));
        return next;
    }


    public final int getAndAccumulate(int x,
                                      IntBinaryOperator accumulatorFunction) {
        int prev, next;
        do {
            prev = get();
            next = accumulatorFunction.applyAsInt(prev, x);
        } while (!compareAndSet(prev, next));
        return prev;
    }


    public final int accumulateAndGet(int x,
                                      IntBinaryOperator accumulatorFunction) {
        int prev, next;
        do {
            prev = get();
            next = accumulatorFunction.applyAsInt(prev, x);
        } while (!compareAndSet(prev, next));
        return next;
    }


    public String toString() {
        return Integer.toString(get());
    }


    public int intValue() {
        return get();
    }


    public long longValue() {
        return (long)get();
    }


    public float floatValue() {
        return (float)get();
    }


    public double doubleValue() {
        return (double)get();
    }

}
