package com.payment.ledger.api;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.trace.TraceContext;
import com.payment.common.dto.rpc.AccountingEventRequest;
import com.payment.common.dto.rpc.AccountingEventResponse;
import com.payment.common.dto.rpc.AccountingEventType;
import com.payment.ledger.application.BalanceChecker;
import com.payment.ledger.application.PeriodService;
import com.payment.ledger.application.PostingEngine;
import com.payment.ledger.domain.AccountBalanceRepository;
import com.payment.ledger.domain.AccountInstance;
import com.payment.ledger.domain.AccountRepository;
import com.payment.ledger.domain.AccountingEvent;
import com.payment.ledger.domain.LedgerEntry;
import com.payment.ledger.domain.LedgerPeriod;
import com.payment.ledger.domain.LedgerRepository;
import com.payment.ledger.domain.LedgerSourceType;
import com.payment.ledger.domain.Posting;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账本内部端点（FR-005：仅被其他服务调用，不反向依赖业务领域）。
 *
 * <p>031 起唯一入账入口是 {@code POST /internal/ledger/accounting-events}：上游只发
 * 已确认财务事实，科目与借贷方向由账本经 Posting Rule 决定（原则 1~6）。
 * 幂等键由 Ledger 派生（{@code {eventType}:{sourceId}}），重复事件回放首次结果。</p>
 */
@RestController
@RequestMapping("/internal/ledger")
public class LedgerController {

    private final PostingEngine postingEngine;
    private final BalanceChecker balanceChecker;
    private final PeriodService periodService;
    private final AccountBalanceRepository balanceRepository;
    private final LedgerRepository ledgerRepository;
    private final AccountRepository accountRepository;
    private final com.payment.common.core.observability.BusinessMetrics metrics;

    public LedgerController(PostingEngine postingEngine,
                            BalanceChecker balanceChecker,
                            PeriodService periodService,
                            AccountBalanceRepository balanceRepository,
                            LedgerRepository ledgerRepository,
                            AccountRepository accountRepository,
                            com.payment.common.core.observability.BusinessMetrics metrics) {
        this.postingEngine = postingEngine;
        this.balanceChecker = balanceChecker;
        this.periodService = periodService;
        this.balanceRepository = balanceRepository;
        this.ledgerRepository = ledgerRepository;
        this.accountRepository = accountRepository;
        this.metrics = metrics;
    }

    /** 记账事件入账（幂等回放；不平衡交易由 Posting 聚合根构造期拒绝，不落任何分录）。
     *  bizNo 取 sourceId（paymentNo/refundNo/batchNo）：入账日志可按业务单号与上游对链（spec 029 FR-605）。 */
    @PostMapping("/accounting-events")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountingEventResponse post(@Valid @RequestBody AccountingEventRequest request) {
        return TraceContext.runWithBizNo(request.sourceId(), () -> {
            Posting posting = postingEngine.post(toDomain(request));
            return toResponse(posting);
        });
    }

    /** 按事件回查（§12：补偿核对入口；重复投递判定「已入账」）。 */
    @GetMapping("/postings")
    public AccountingEventResponse find(@RequestParam String eventType, @RequestParam String sourceId) {
        return ledgerRepository.findByEvent(parseEventType(eventType), sourceId)
                .map(this::toResponse)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND,
                        "posting not found: " + eventType + ":" + sourceId));
    }

    /** 全部账本交易及分录（spec 017：审计账证/账账核对只读入口，按 id 倒序，上限 1000）。 */
    @GetMapping("/postings/all")
    public List<AccountingEventResponse> allPostings(@RequestParam(required = false) String sourceType) {
        List<Posting> postings = ledgerRepository.findAllPostings();
        if (sourceType != null && !sourceType.isBlank()) {
            LedgerSourceType st = LedgerSourceType.valueOf(sourceType);
            postings = postings.stream().filter(p -> p.getSourceType() == st).toList();
        }
        Map<Long, AccountInstance> instances = instanceIndex();
        return postings.stream().map(p -> toResponse(p, instances)).toList();
    }

    /** 全局借贷平衡性校验（FR-007）。 */
    @GetMapping("/balance")
    public BalanceCheckView balance() {
        return new BalanceCheckView(balanceChecker.isBalanced(), balanceChecker.byCurrency());
    }

    /** 试算平衡表（G1；period 可选，YYYY-MM，G2）。 */
    @GetMapping("/trial-balance")
    public List<BalanceChecker.TrialBalanceView> trialBalance(
            @RequestParam(required = false) String period) {
        return balanceChecker.trialBalance(period);
    }

    /** 分户余额（G1，读投影）。 */
    @GetMapping("/accounts/{accountId}/balance")
    public BalanceChecker.BalanceView accountBalance(@PathVariable Long accountId,
                                                     @RequestParam String currency) {
        return balanceChecker.balanceOf(accountId, currency);
    }

    /** 余额列表（科目 × 商户/渠道维度；currency 可选过滤）。 */
    @GetMapping("/balances")
    public List<BalanceChecker.BalanceView> balances(@RequestParam(required = false) String currency) {
        return balanceChecker.balances(currency);
    }

    /** 全量重建余额投影（§10 低频管理端点：以 ledger_entries 为准重算）。
     *  spec 035 / A-11：每次 rebuild 计数——正常应 ≈0，出现即投影漂移过（须查根因）。 */
    @PostMapping("/balances/rebuild")
    public Map<String, Integer> rebuildBalances() {
        int rebuilt = balanceRepository.rebuild();
        metrics.counter("balance_rebuild_applied", 1.0, "module", "ledger");
        return Map.of("rebuilt", rebuilt);
    }

    /** 期间列表（缺行 = OPEN 不在列；仅显式建行/关账后出现）。 */
    @GetMapping("/periods")
    public List<PeriodView> periods() {
        return periodService.list().stream()
                .map(p -> new PeriodView(p.getPeriod(), p.getCurrency(), p.getStatus().name(),
                        p.getClosedAt(), p.getClosedBy()))
                .toList();
    }

    /** 关账（§11 前置 = 试算平衡 且 无 PENDING；CLOSED 后拒收新事件）。 */
    @PostMapping("/periods/{period}/close")
    public PeriodView closePeriod(@PathVariable String period,
                                  @RequestParam(defaultValue = "system") String closedBy) {
        LedgerPeriod p = periodService.close(period, closedBy);
        return new PeriodView(p.getPeriod(), p.getCurrency(), p.getStatus().name(),
                p.getClosedAt(), p.getClosedBy());
    }

    /** 按业务来源追溯（FR-008；分录经 posting join 取回，含事件语义）。 */
    @GetMapping("/entries")
    public List<AccountingEventResponse> entries(@RequestParam String sourceType,
                                                 @RequestParam String sourceId) {
        LedgerSourceType st = LedgerSourceType.valueOf(sourceType);
        Map<Long, AccountInstance> instances = instanceIndex();
        return ledgerRepository.findBySource(st, sourceId).stream()
                .map(p -> toResponse(p, instances))
                .toList();
    }

    private static AccountingEvent toDomain(AccountingEventRequest request) {
        LedgerSourceType sourceType;
        try {
            sourceType = LedgerSourceType.valueOf(request.sourceType());
        } catch (IllegalArgumentException e) {
            throw BizException.of(ErrorCodes.EVENT_FIELD_MISSING,
                    "unknown sourceType: " + request.sourceType());
        }
        String reversesEventType = request.reversesEventType();
        return new AccountingEvent(parseEventType(request.eventType()), sourceType,
                request.sourceId(), request.currency(),
                request.grossAmountMinor(), request.merchantFeeMinor(), request.channelFeeMinor(),
                request.merchantId(), request.channelCode(), request.netAmountMinor(),
                request.adjustmentKind(), request.fromAccountCode(), request.toAccountCode(),
                request.amountMinor(),
                reversesEventType == null || reversesEventType.isBlank()
                        ? null : parseEventType(reversesEventType),
                request.reversesSourceId());
    }

    private static AccountingEventType parseEventType(String value) {
        try {
            return AccountingEventType.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw BizException.of(ErrorCodes.EVENT_TYPE_UNSUPPORTED, "unknown eventType: " + value);
        }
    }

    /** 交易 → 响应；分录回显账户语义（definitionCode + owner），实例索引由调用方单次构建避免 N+1。 */
    private AccountingEventResponse toResponse(Posting posting) {
        return toResponse(posting, instanceIndex());
    }

    private AccountingEventResponse toResponse(Posting posting, Map<Long, AccountInstance> instances) {
        List<AccountingEventResponse.EntryView> views = new ArrayList<>();
        for (LedgerEntry e : posting.getEntries()) {
            AccountInstance instance = instances.get(e.getAccountId());
            views.add(new AccountingEventResponse.EntryView(e.getId(), e.getAccountId(),
                    instance == null ? null : instance.getDefinitionCode(),
                    instance == null ? null : instance.getOwnerType().name(),
                    instance == null ? null : instance.getOwnerId(),
                    e.getDirection().name(), e.getAmountMinor(), e.getCurrency()));
        }
        return new AccountingEventResponse(posting.getId(), posting.getPostingNo(),
                posting.getEventType().name(), posting.getIdempotencyKey(),
                posting.getSourceType().name(), posting.getSourceId(), posting.getCurrency(),
                posting.getPeriod(), posting.getPostedAt(), posting.getStatus().name(), views);
    }

    private Map<Long, AccountInstance> instanceIndex() {
        Map<Long, AccountInstance> index = new java.util.HashMap<>();
        accountRepository.findAllInstances().forEach(i -> index.put(i.getId(), i));
        return index;
    }

    /** 平衡性校验结果：是否平衡 + 各币种借贷差额。 */
    public record BalanceCheckView(boolean balanced, Map<String, Long> diffByCurrency) {
    }

    /** 期间视图。 */
    public record PeriodView(String period, String currency, String status,
                             java.time.Instant closedAt, String closedBy) {
    }
}
