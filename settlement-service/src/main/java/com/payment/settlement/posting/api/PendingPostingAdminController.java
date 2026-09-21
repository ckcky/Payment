package com.payment.settlement.posting.api;

import com.payment.settlement.posting.application.PostingProperties;
import com.payment.settlement.posting.application.PostingReplayer;
import com.payment.settlement.posting.domain.PendingPosting;
import com.payment.settlement.posting.domain.PendingPostingRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 出站失败台账管理端点（spec 034 §9.1 / §12.1）：人工队列视图 + 人工重放。
 *
 * <p>路径对齐 spec §9.1 {{@code /internal/{service}/pending-postings}} 模板（settlement 落点）。</p>
 *
 * <p>守卫：{@code PostingAdminGuardInterceptor}（X-Admin-Token，{@code PostingWebConfig} 注册）——
 * 与 {@code resolve} 人工收敛同风格（spec §1.1#5 先例）；启用鉴权但未配置 token 时 503 锁死。</p>
 *
 * <p><b>人工 replay 语义（spec §12.1）</b>：立即执行一次投递——成功置 REPOSTED（终态）；
 * 失败重置 retry_count 重新进入自动退避（ABANDONED 行只有经此入口复活，人工可追溯）。
 * 重放不绕过目的地幂等（Ledger 派生键），不产生第二事实。</p>
 */
@RestController
@RequestMapping("/internal/settlements/pending-postings")
public class PendingPostingAdminController {

    private final PendingPostingRepository repository;
    private final PostingReplayer replayer;
    private final int listLimit;

    public PendingPostingAdminController(PendingPostingRepository repository,
                                         PostingReplayer replayer,
                                         PostingProperties properties) {
        this.repository = repository;
        this.replayer = replayer;
        this.listLimit = properties.getListLimit();
    }

    /** 人工队列视图（只读聚合，按 status 过滤，id 升序）。 */
    @GetMapping
    public List<PendingPostingResponse> list(
            @RequestParam(name = "status", defaultValue = "PENDING") String status) {
        PendingPosting.PostingStatus parsed = PendingPosting.PostingStatus.valueOf(status);
        return repository.findByStatus(parsed, listLimit).stream()
                .map(PendingPostingResponse::from)
                .toList();
    }

    /** 人工重放一行台账（REPOSTED 终态行不重放 → 409）。 */
    @PostMapping("/{id}/replay")
    public ResponseEntity<PendingPostingResponse> replay(@PathVariable Long id) {
        PendingPosting posting = repository.findById(id).orElse(null);
        if (posting == null) {
            return ResponseEntity.notFound().build();
        }
        if (posting.getStatus() == PendingPosting.PostingStatus.REPOSTED) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(PendingPostingResponse.from(posting));
        }
        try {
            boolean ok = replayer.replay(posting);
            if (ok) {
                posting.applyReplaySuccess();
            } else {
                posting.resetForManualReplay("manual replay returned failure");
            }
        } catch (RuntimeException ex) {
            String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            posting.resetForManualReplay(reason.length() > 512 ? reason.substring(0, 512) : reason);
        }
        repository.update(posting);
        return ResponseEntity.ok(PendingPostingResponse.from(posting));
    }

    /** 台账行视图（不含载荷原文——payload 可能很大，需要时按 id 精确查库）。 */
    public record PendingPostingResponse(Long id, String eventType, String sourceType, String sourceId,
                                         String idempotencyKey, String failReason,
                                         int retryCount, String status) {

        static PendingPostingResponse from(PendingPosting posting) {
            return new PendingPostingResponse(posting.getId(), posting.getEventType(),
                    posting.getSourceType(), posting.getSourceId(), posting.getIdempotencyKey(),
                    posting.getFailReason(), posting.getRetryCount(), posting.getStatus().name());
        }
    }
}
