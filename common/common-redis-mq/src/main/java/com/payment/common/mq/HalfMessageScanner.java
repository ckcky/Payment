package com.payment.common.mq;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 半消息回查扫描器（spec 029 / T18-T19 / FR-106、FR-111）。
 *
 * <p>每 5s 扫 {@code mq:half:idx} 中 score 早于「当前 - prepareTimeout」的条目，读回半消息，
 * 分派给能处理它的 {@link TransactionChecker}：</p>
 * <ul>
 *   <li>COMMIT → 补投（XADD 可见队列）后清半消息</li>
 *   <li>ROLLBACK → 直接清半消息，不投递</li>
 *   <li>UNKNOWN → 累计回查次数，超 {@code maxCheckTimes} 进 DLQ + 告警</li>
 * </ul>
 *
 * <p><b>FR-603</b>：补投 MUST 沿用信封中的原始 traceId，**禁止** {@code runWithNewTrace} 新建——
 * 否则这条补投线索与原始链路断裂。故此处只把 traceId 放回 MDC 便于打日志，不生成新值。</p>
 */
public class HalfMessageScanner {

    private static final Logger log = LoggerFactory.getLogger(HalfMessageScanner.class);

    /** UNKNOWN 累计计数键前缀（Hash field 计数，避免无限增长需配合清理）。 */
    private static final String CHECK_COUNT_PREFIX = "mq:half:checks:";

    private final TransactionalProducer producer;
    private final MqProperties props;
    private final BusinessMetrics metrics;
    private final List<TransactionChecker> checkers;

    public HalfMessageScanner(TransactionalProducer producer, MqProperties props,
                              BusinessMetrics metrics, List<TransactionChecker> checkers) {
        this.producer = producer;
        this.props = props;
        this.metrics = metrics;
        this.checkers = checkers == null ? List.of() : checkers;
    }

    @Scheduled(fixedDelayString = "${payment.mq.scan-interval-ms:5000}")
    public void scan() {
        if (!props.isEnabled()) {
            return;
        }
        // FR-111：扫描线程不经过 TraceIdFilter，须自建 trace 上下文（否则日志 traceId=N/A）
        TraceContext.runWithNewTrace(this::doScan);
    }

    private void doScan() {
        long cutoff = System.currentTimeMillis() - props.getPrepareTimeoutMs().toMillis();
        Set<String> due;
        try {
            due = producer != null
                    ? producer.dueHalfMessages(cutoff)
                    : Set.of();
        } catch (RuntimeException e) {
            log.warn("半消息扫描失败（下轮重试）", e);
            return;
        }
        // FR-502 / T55：半消息积压观测（本轮到期条数，供告警阈值）
        if (metrics != null && !due.isEmpty()) {
            metrics.counter("mq.half_backlog", due.size(), "topic", "all");
        }
        for (String member : due) {
            // member = topic:msgId
            int idx = member.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String topic = member.substring(0, idx);
            String msgId = member.substring(idx + 1);
            try {
                checkOne(topic, msgId);
            } catch (RuntimeException e) {
                log.warn("半消息回查异常（下轮重试）[member={}]", member, e);
            }
        }
    }

    private void checkOne(String topic, String msgId) {
        EventEnvelope envelope = producer.readHalf(topic, msgId);
        if (envelope == null) {
            // 半消息已被 commit/rollback 清理，索引残留 → 清索引
            producer.removeHalfIndex(topic, msgId);
            return;
        }
        TransactionChecker.LocalTxState state = TransactionChecker.LocalTxState.UNKNOWN;
        for (TransactionChecker checker : checkers) {
            if (checker.supports(envelope)) {
                state = checker.check(envelope);
                break;
            }
        }
        if (metrics != null) {
            metrics.counter("mq.checked", 1, "topic", topic, "state", state.name());
        }
        switch (state) {
            case COMMIT -> {
                // FR-603：沿用原始 traceId 补投（不新建）
                log.info("半消息回查 → COMMIT 补投 [topic={}, msgId={}, traceId={}]",
                        topic, msgId, envelope.traceId());
                producer.commit(envelope);
            }
            case ROLLBACK -> {
                log.info("半消息回查 → ROLLBACK 丢弃 [topic={}, msgId={}]", topic, msgId);
                producer.rollback(envelope);
                producer.clearCheckCount(topic, msgId);
            }
            case UNKNOWN -> {
                int n = producer.incrementCheckCount(topic, msgId);
                if (n >= props.getMaxCheckTimes()) {
                    log.error("半消息回查 UNKNOWN 超 {} 次 → 进 DLQ [topic={}, msgId={}]",
                            props.getMaxCheckTimes(), topic, msgId);
                    producer.toDlq(envelope, "UNKNOWN 回查超次");
                    producer.cleanup(envelope);
                    producer.clearCheckCount(topic, msgId);
                }
            }
        }
    }
}
