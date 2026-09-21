package com.payment.settlement.posting.application;

import com.payment.common.core.trace.TraceContext;
import com.payment.settlement.posting.domain.PendingPosting;
import com.payment.settlement.posting.domain.PendingPostingRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 台账补投调度器（spec 034 §12.1 恢复时序）：每 {@code payment.posting.retry-interval-ms}
 * （默认 10s）扫到期待补投的 PENDING 行，逐行原样重发。
 *
 * <p><b>节奏（spec §5 X-6 / plan §2.2）</b>：登记后 1s 首补，失败退避 5s/30s/2m/10m；
 * retry_count 达 6（首次登记 + 5 次补投全败）→ ABANDONED + critical 语义计数，
 * 退出自动补投等待人工 {@code POST /internal/postings/{id}/replay}。
 * 成功 → REPOSTED（终态保留供审计）。</p>
 *
 * <p><b>幂等前提</b>：Ledger 按 {@code {eventType}:{sourceId}} 派生键吸收重复（031 §9），
 * order 消费端按终态吸收重复——重放绝不双记（台账可重试的前提）。</p>
 *
 * <p><b>可观测</b>（诊断①）：调度线程不经 TraceIdFilter，入口
 * {@code TraceContext.runWithNewTrace} 自建 traceId，后台日志可被 trace-grep 捞取。</p>
 */
@Component
public class PostingRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(PostingRetryScheduler.class);
    private static final String MODULE = "posting";

    private final PendingPostingRepository repository;
    private final PostingReplayer replayer;
    private final PostingProperties properties;
    private final com.payment.common.core.observability.BusinessMetrics metrics;

    public PostingRetryScheduler(PendingPostingRepository repository,
                                 PostingReplayer replayer,
                                 PostingProperties properties,
                                 com.payment.common.core.observability.BusinessMetrics metrics) {
        this.repository = repository;
        this.replayer = replayer;
        this.properties = properties;
        this.metrics = metrics;
    }

    /** 调度入口（spec 034 / 诊断①）：新 traceId 包裹整轮补投。 */
    @Scheduled(fixedDelayString = "${payment.posting.retry-interval-ms:10000}")
    public void run() {
        TraceContext.runWithNewTrace(this::retryRound);
    }

    /** 补投一轮：返回成功补投行数（测试与观测用）。 */
    public int retryRound() {
        List<PendingPosting> due = repository.findDuePending(
                Instant.now(), properties.getRetryBackoff(), properties.getBatchSize());
        int reposted = 0;
        for (PendingPosting posting : due) {
            try {
                boolean ok = replayer.replay(posting);
                if (ok) {
                    posting.applyReplaySuccess();
                    metrics.counter("ledger.posting_reposted", 1.0, "module", MODULE);
                    log.info("台账补投成功 event={} sourceId={}", posting.getEventType(), posting.getSourceId());
                } else {
                    posting.recordRetryFailure("replay returned failure");
                    metrics.counter("ledger.posting_retry_failed", 1.0, "module", MODULE);
                }
            } catch (RuntimeException ex) {
                posting.recordRetryFailure(ex.getMessage() == null
                        ? ex.getClass().getSimpleName() : ex.getMessage());
                metrics.counter("ledger.posting_retry_failed", 1.0, "module", MODULE);
                log.warn("台账补投失败 event={} sourceId={} retryCount={} reason={}",
                        posting.getEventType(), posting.getSourceId(), posting.getRetryCount(), ex.getMessage());
            }
            if (posting.getStatus() == PendingPosting.PostingStatus.ABANDONED) {
                // 耗尽显性化（R-2）：critical 语义计数 + 告警由 A-09 挂在该计数上
                metrics.counter("ledger_posting_abandoned", 1.0, "module", MODULE,
                        "eventType", posting.getEventType());
                log.error("台账补投耗尽置 ABANDONED（等待人工 replay）event={} sourceId={} reason={}",
                        posting.getEventType(), posting.getSourceId(), posting.getFailReason());
            }
            repository.update(posting);
            if (posting.getStatus() == PendingPosting.PostingStatus.REPOSTED) {
                reposted++;
            }
        }
        return reposted;
    }
}
