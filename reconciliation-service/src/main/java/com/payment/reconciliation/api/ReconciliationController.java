package com.payment.reconciliation.api;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.reconciliation.application.ChannelFundPostingService;
import com.payment.reconciliation.application.ReconciliationApplicationService;
import com.payment.reconciliation.application.StatementImportApplicationService;
import com.payment.reconciliation.domain.ReconciliationBatch;
import com.payment.reconciliation.domain.ReconciliationDifference;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 对账服务对外 REST 接口（内部同步 RPC 面）。
 *
 * <p>032 实账化端点（spec §10.1/§10.2）：账单导入台账、run 编排（无导入 400
 * {@code STATEMENT_UNAVAILABLE}）、差异台账分页查询与人工收口、渠道资金事实入账。</p>
 */
@RestController
@RequestMapping("/internal/reconciliation")
public class ReconciliationController {

    private final ReconciliationApplicationService applicationService;
    private final StatementImportApplicationService statementImportService;
    private final ChannelFundPostingService channelFundPostingService;

    public ReconciliationController(ReconciliationApplicationService applicationService,
                                    StatementImportApplicationService statementImportService,
                                    ChannelFundPostingService channelFundPostingService) {
        this.applicationService = applicationService;
        this.statementImportService = statementImportService;
        this.channelFundPostingService = channelFundPostingService;
    }

    /** 执行对账（spec §10.1 run）：缺省取该渠道该周期最新 NORMALIZED 导入，无导入 ⇒ 400。 */
    @PostMapping("/run")
    public ReconciliationBatchResponse run(@Valid @RequestBody RunReconciliationRequest request) {
        ReconciliationBatch batch = applicationService.runReconciliation(
                request.period(), request.channelCode(), request.importNo());
        return ReconciliationBatchResponse.from(batch);
    }

    /** 兼容入口（032 前形态）：等价 {@code POST /run}（缺省渠道与最新导入）。 */
    @PostMapping("/batches")
    public ReconciliationBatchResponse runReconciliation(@RequestBody RunReconciliationRequest request) {
        ReconciliationBatch batch = applicationService.runReconciliation(request.period());
        return ReconciliationBatchResponse.from(batch);
    }

    @GetMapping("/batches/{id}")
    public ReconciliationBatchResponse getBatch(@PathVariable Long id) {
        return ReconciliationBatchResponse.from(applicationService.getBatch(id));
    }

    @GetMapping("/batches/{id}/differences")
    public List<DifferenceResponse> listDifferences(@PathVariable Long id) {
        return applicationService.listDifferences(id).stream()
                .map(DifferenceResponse::from)
                .toList();
    }

    @PostMapping("/batches/{id}/differences/resolve")
    public DifferenceResponse resolveDifference(@PathVariable Long id,
                                                @Valid @RequestBody ResolveDifferenceRequest request) {
        return DifferenceResponse.from(applicationService.resolveDifference(
                id, request.reference(), request.resolutionNote(), request.resolvedBy(), request.resolvedAt()));
    }

    @PostMapping("/batches/{id}/close")
    public ReconciliationBatchResponse closeBatch(@PathVariable Long id,
                                                  @RequestBody CloseBatchRequest request) {
        return ReconciliationBatchResponse.from(applicationService.closeBatch(id, request.operator()));
    }

    @GetMapping("/settlement-summary")
    public ReconciliationSettlementSummaryResponse settlementSummary(@RequestParam String period) {
        return applicationService.settlementSummary(period);
    }

    // ---------------------------------------------------------------- 032 账单导入台账

    /** 导入账单（spec §10.1）：内容指纹（SHA-256）幂等——同指纹重放返回首次导入；结构性失败整批 REJECTED。 */
    @PostMapping("/statement-imports")
    @ResponseStatus(HttpStatus.CREATED)
    public StatementImportResponse importStatement(@Valid @RequestBody StatementImportRequest request) {
        return StatementImportResponse.from(statementImportService.importStatement(
                request.channelCode(), request.period(), request.sourceType(),
                request.content(), request.importedBy()));
    }

    /** 导入台账查询（spec §10.1）：含 status / rowCount / errorReason。 */
    @GetMapping("/statement-imports")
    public List<StatementImportResponse> listStatementImports(@RequestParam(required = false) String period,
                                                              @RequestParam(required = false) String channelCode) {
        return statementImportService.listImports(period, channelCode).stream()
                .map(StatementImportResponse::from)
                .toList();
    }

    /** 导入详情（含解析状态与拒绝原因）。 */
    @GetMapping("/statement-imports/{id}")
    public StatementImportResponse getStatementImport(@PathVariable Long id) {
        return statementImportService.getImport(id)
                .map(StatementImportResponse::from)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND,
                        "statement import not found: " + id));
    }

    // ---------------------------------------------------------------- 032 差异台账（拆表后可分页）

    /** 差异分页查询（spec §10.1 GET differences）：period / status / merchantId / kind 过滤。 */
    @GetMapping("/differences")
    public DifferencesPageResponse differences(@RequestParam(required = false) String period,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) String merchantId,
                                               @RequestParam(required = false) String kind,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return DifferencesPageResponse.from(
                applicationService.searchDifferences(period, status, merchantId, kind, page, size),
                Math.max(0, page), Math.min(Math.max(1, size), 200));
    }

    /** 差异台账详情（RD 单号）。 */
    @GetMapping("/differences/{diffNo}")
    public DifferenceLedgerResponse difference(@PathVariable String diffNo) {
        return DifferenceLedgerResponse.from(requireDifference(diffNo));
    }

    /** 人工收口（spec §10.1，主入口）：按 RD 单号 resolve，备注必填（ADR-0019）。 */
    @PostMapping("/differences/{diffNo}/resolve")
    public DifferenceLedgerResponse resolveDifference(@PathVariable String diffNo,
                                                      @Valid @RequestBody ResolveByNoRequest request) {
        return DifferenceLedgerResponse.from(applicationService.resolveDifferenceByNo(
                diffNo, request.resolutionNote(), request.resolvedBy(), request.resolvedAt()));
    }

    // ---------------------------------------------------------------- 032 渠道资金事实入账（G3）

    /**
     * 触发渠道资金事实入账（spec §10.2）：CHANNEL_SETTLEMENT / CHANNEL_FEE 事件入 ledger；
     * 零净额/零费用跳过；sourceId 确定性派生，重放幂等；无可用账单 ⇒ 400 STATEMENT_UNAVAILABLE。
     */
    @PostMapping("/channels/{channelCode}/fund-facts/{period}/post")
    public FundPostingResponse postChannelFundFacts(@PathVariable String channelCode,
                                                    @PathVariable String period) {
        ChannelFundPostingService.PostingOutcome outcome =
                channelFundPostingService.postChannelFundFacts(channelCode, period);
        return FundPostingResponse.from(outcome.fact(), outcome.postedEvents());
    }

    private ReconciliationDifference requireDifference(String diffNo) {
        return applicationService.getDifference(diffNo)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND,
                        "difference not found: " + diffNo));
    }
}
