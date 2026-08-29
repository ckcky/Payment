package com.payment.ledger.api;

import com.payment.common.dto.rpc.PostingRequest;
import com.payment.common.dto.rpc.PostingResponse;
import com.payment.ledger.application.BalanceChecker;
import com.payment.ledger.application.LedgerPostingService;
import com.payment.ledger.domain.LedgerEntry;
import com.payment.ledger.domain.LedgerRepository;
import com.payment.ledger.domain.LedgerSourceType;
import com.payment.ledger.domain.Posting;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账本内部端点（FR-005：仅被其他服务调用，不反向依赖业务领域）。
 *
 * <p>记账入口接受调用方幂等键；借贷不平衡的请求由 {@link Posting} 聚合根拒绝，不落任何分录。</p>
 */
@RestController
@RequestMapping("/internal/ledger")
public class LedgerController {

    private final LedgerPostingService postingService;
    private final BalanceChecker balanceChecker;
    private final LedgerRepository ledgerRepository;

    public LedgerController(LedgerPostingService postingService,
                            BalanceChecker balanceChecker,
                            LedgerRepository ledgerRepository) {
        this.postingService = postingService;
        this.balanceChecker = balanceChecker;
        this.ledgerRepository = ledgerRepository;
    }

    /** 记账（幂等）：相同幂等键返回首次结果，不重复生成分录。 */
    @PostMapping("/postings")
    @ResponseStatus(HttpStatus.CREATED)
    public PostingResponse post(@Valid @RequestBody PostingRequest request) {
        LedgerSourceType sourceType = LedgerSourceType.valueOf(request.sourceType());
        List<LedgerEntry> entries = new ArrayList<>();
        for (PostingRequest.EntryRequest entry : request.entries()) {
            entries.add(new LedgerEntry(null, entry.accountId(),
                    LedgerEntry.Direction.valueOf(entry.direction()), entry.amountMinor(),
                    request.currency(), LedgerEntry.Type.valueOf(entry.entryType()),
                    sourceType, request.sourceId()));
        }
        Posting posting = postingService.post(request.idempotencyKey(), sourceType,
                request.sourceId(), request.currency(), entries);
        return toResponse(posting);
    }

    /** 按幂等键回查。 */
    @GetMapping("/postings")
    public PostingResponse find(@RequestParam String idempotencyKey) {
        return ledgerRepository.findByIdempotencyKey(idempotencyKey)
                .map(LedgerController::toResponse)
                .orElseThrow(() -> new IllegalArgumentException("posting not found: " + idempotencyKey));
    }

    /** 全局借贷平衡性校验（FR-007）。 */
    @GetMapping("/balance")
    public BalanceView balance() {
        return new BalanceView(balanceChecker.isBalanced(), balanceChecker.byCurrency());
    }

    /** 按业务来源追溯分录（FR-008）。 */
    @GetMapping("/entries")
    public List<PostingResponse.EntryView> entries(@RequestParam String sourceType,
                                                   @RequestParam String sourceId) {
        return balanceChecker.entriesOfSource(LedgerSourceType.valueOf(sourceType), sourceId).stream()
                .map(e -> new PostingResponse.EntryView(e.getId(), e.getAccountId(),
                        e.getDirection().name(), e.getAmountMinor(), e.getCurrency(),
                        e.getEntryType().name()))
                .toList();
    }

    private static PostingResponse toResponse(Posting posting) {
        List<PostingResponse.EntryView> views = posting.getEntries().stream()
                .map(e -> new PostingResponse.EntryView(e.getId(), e.getAccountId(),
                        e.getDirection().name(), e.getAmountMinor(), e.getCurrency(),
                        e.getEntryType().name()))
                .toList();
        return new PostingResponse(posting.getId(), posting.getIdempotencyKey(),
                posting.getSourceType().name(), posting.getSourceId(), posting.getCurrency(),
                posting.getStatus().name(), views);
    }

    /** 平衡性校验结果：是否平衡 + 各币种借贷差额。 */
    public record BalanceView(boolean balanced, Map<String, Long> diffByCurrency) {
    }
}
