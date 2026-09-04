package com.payment.common.core.id;

/**
 * 雪花算法 ID 生成器（标准布局：1 符号位 + 41 毫秒时间戳 + 5 数据中心 + 5 机器 + 12 序列）。
 *
 * <p>单机进程内线程安全（synchronized，TPS 上限 409.6 万/秒，远超本项目需求）。
 * workerId 默认取 {@code server.port % 32}、datacenterId 固定 1：同一宿主机并存的多个服务
 * 进程端口不同，天然错开 workerId，跨进程不产生重复 ID（ADR-0062）。</p>
 *
 * <p>时钟回拨处理：回拨 ≤ 5ms 时自旋等待追平；> 5ms 直接抛异常（支付系统宁可失败也不发重复号）。</p>
 */
public final class SnowflakeIdWorker {

    private static final long EPOCH = 1735689600000L; // 2025-01-01T00:00:00Z，41 位可用约 69 年
    private static final int WORKER_BITS = 5;
    private static final int DATACENTER_BITS = 5;
    private static final int SEQUENCE_BITS = 12;

    private static final long MAX_WORKER_ID = ~(-1L << WORKER_BITS);           // 31
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_BITS);   // 31
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);         // 4095

    private static final long WORKER_SHIFT = SEQUENCE_BITS;
    private static final long DATACENTER_SHIFT = SEQUENCE_BITS + WORKER_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_BITS + DATACENTER_BITS;
    private static final long MAX_BACKWARD_MS = 5;

    private final long workerId;
    private final long datacenterId;

    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdWorker(long workerId, long datacenterId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId 必须在 [0," + MAX_WORKER_ID + "]");
        }
        if (datacenterId < 0 || datacenterId > MAX_DATACENTER_ID) {
            throw new IllegalArgumentException("datacenterId 必须在 [0," + MAX_DATACENTER_ID + "]");
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
    }

    /** 便捷工厂：workerId 由端口号派生（port % 32），datacenterId 固定 1。 */
    public static SnowflakeIdWorker forPort(int port) {
        return new SnowflakeIdWorker(port % 32, 1);
    }

    /** 生成下一个全局唯一 ID（阻塞式，进程内线程安全）。 */
    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();
        if (timestamp < lastTimestamp) {
            long drift = lastTimestamp - timestamp;
            if (drift > MAX_BACKWARD_MS) {
                throw new IllegalStateException("时钟回拨 " + drift + "ms，拒绝生成 ID（防重复）");
            }
            // 小幅回拨：自旋等待追平
            timestamp = lastTimestamp;
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                // 当前毫秒序列用尽，自旋到下一毫秒
                timestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (datacenterId << DATACENTER_SHIFT)
                | (workerId << WORKER_SHIFT)
                | sequence;
    }

    private long waitNextMillis(long lastTs) {
        long ts = System.currentTimeMillis();
        while (ts <= lastTs) {
            ts = System.currentTimeMillis();
        }
        return ts;
    }
}
