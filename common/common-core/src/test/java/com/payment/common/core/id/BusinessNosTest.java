package com.payment.common.core.id;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 雪花单号组件测试（ADR-0062）。
 */
class BusinessNosTest {

    @Test
    void snowflake_并发唯一性() throws Exception {
        SnowflakeIdWorker worker = new SnowflakeIdWorker(1, 1);
        int threads = 8, perThread = 5_000;
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        CountDownLatch latch = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    ids.add(worker.nextId());
                }
                latch.countDown();
            });
        }
        latch.await();
        pool.shutdown();
        assertEquals(threads * perThread, ids.size(), "40k 并发生成不得重复");
    }

    @Test
    void snowflake_非法参数() {
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdWorker(32, 1));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdWorker(1, 32));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdWorker(-1, 1));
    }

    @Test
    void businessNo_格式与校验() {
        String orderNo = BusinessNos.of(BusinessNoType.ORDER);
        assertTrue(orderNo.startsWith("OR"));
        assertTrue(orderNo.length() >= 20 && orderNo.length() <= 21, "实际长度=" + orderNo.length());
        assertTrue(BusinessNos.isValid(orderNo, BusinessNoType.ORDER));
        assertFalse(BusinessNos.isValid(orderNo, BusinessNoType.PAYMENT), "前缀不匹配");
        assertFalse(BusinessNos.isValid("OR12ab78", BusinessNoType.ORDER), "后缀必须纯数字");
        assertFalse(BusinessNos.isValid(null, BusinessNoType.ORDER));
    }

    @Test
    void businessNo_各类型前缀() {
        for (BusinessNoType type : BusinessNoType.values()) {
            String no = BusinessNos.of(type);
            assertTrue(no.startsWith(type.prefix()), type + " 前缀缺失: " + no);
        }
    }

    @Test
    void businessNo_批量生成无重复() {
        List<String> nos = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            nos.add(BusinessNos.of(BusinessNoType.PAYMENT));
        }
        assertEquals(10_000, Set.copyOf(nos).size());
    }

    // ---- spec 037 / FR-001：渠道网关业务单号 channelNo（CH + 雪花） ----

    @Test
    void channelNo_前缀与校验() {
        String channelNo = BusinessNos.of(BusinessNoType.CHANNEL);
        assertTrue(channelNo.startsWith("CH"), "前缀应为 CH，实际=" + channelNo);
        assertTrue(BusinessNos.isValid(channelNo, BusinessNoType.CHANNEL), "应通过自身类型校验，实际=" + channelNo);
        assertFalse(BusinessNos.isValid(channelNo, BusinessNoType.PAYMENT), "前缀不匹配不得通过");
        assertTrue(channelNo.length() >= 20 && channelNo.length() <= 21,
                "应与 paymentNo 同构（CH + 18~19 位雪花），实际长度=" + channelNo.length());
    }

    @Test
    void channelNo_并发生成不重复() throws Exception {
        int threads = 8, perThread = 1_250;   // 合计 10k
        Set<String> nos = ConcurrentHashMap.newKeySet();
        CountDownLatch latch = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    nos.add(BusinessNos.of(BusinessNoType.CHANNEL));
                }
                latch.countDown();
            });
        }
        latch.await();
        pool.shutdown();
        assertEquals(threads * perThread, nos.size(), "10k 并发 channelNo 不得重复");
    }
}
