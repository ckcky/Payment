package com.payment.posting.infra;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.posting.domain.PendingPosting.PostingStatus;
import com.payment.posting.domain.PendingPostingRepository;
import org.springframework.stereotype.Component;

/**
 * 台账 gauge 绑定（spec 034 / T9）：{@code ledger_posting_pending} = PENDING 行数
 * （含已到期待补投与退避等待中的行），抓取期采样（Prometheus scrape 频率即采样频率）。
 * ABANDONED 走计数器 {@code ledger_posting_abandoned}（A-09 告警挂靠），不在此重复。
 */
@Component
public class PostingGaugeBinder {

    public PostingGaugeBinder(PendingPostingRepository repository, BusinessMetrics metrics) {
        metrics.gauge("ledger_posting_pending", () -> {
            try {
                return repository.countByStatus(PostingStatus.PENDING);
            } catch (RuntimeException ex) {
                // 抓取期 DB 抖动不传播：返回 null = Micrometer 记 NaN，下轮抓取恢复
                return null;
            }
        }, "module", "posting");
    }
}
