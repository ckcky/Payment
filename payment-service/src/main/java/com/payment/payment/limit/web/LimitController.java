package com.payment.payment.limit.web;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.payment.limit.application.LimitPendingRecycler;
import com.payment.payment.limit.application.LimitQueryService;
import com.payment.payment.limit.application.LimitReserveService;
import com.payment.payment.limit.application.LimitSettlementService;
import com.payment.payment.limit.domain.LimitPeriod;
import com.payment.payment.limit.domain.UserPaymentLimit;
import com.payment.payment.limit.domain.UserPaymentLimitRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户限额内部端点（spec 027 / FR-020，ADR-0071）。
 *
 * <p><b>归属与暴露范围</b>：{@code /internal/**} 是服务间与运维通道（ADR-0024 预留鉴权、
 * ADR-0063 一律用业务单号寻址）。本端点<b>只被演示组件与运维使用</b>，不被 order-service
 * 调用——限额判定在支付侧，order 不持有写数据（D1）。</p>
 *
 * <p><b>写操作的性质</b>（D9）：{@code PUT} 改的是<b>限额配置</b>，不产生任何资金动作——
 * 这与 ADR-0048「禁止演示页伪造业务事实」是两回事，故演示组件对其开放同源代理，
 * 并在 ADR-0071 登记为 ADR-0048 的显式例外（例外范围<b>仅限</b> {@code /internal/limits/**}）。</p>
 */
@RestController
@RequestMapping("/internal/limits")
public class LimitController {

    private final UserPaymentLimitRepository limitRepository;
    private final LimitQueryService queryService;
    private final LimitPendingRecycler recycler;
    private final LimitSettlementService settlementService;

    public LimitController(UserPaymentLimitRepository limitRepository,
                           LimitQueryService queryService,
                           LimitPendingRecycler recycler,
                           LimitSettlementService settlementService) {
        this.limitRepository = limitRepository;
        this.queryService = queryService;
        this.recycler = recycler;
        this.settlementService = settlementService;
    }

    /**
     * 查询用户额度快照（FR-020）：三周期 limit / used / pending / available + overrun + 配置。
     *
     * @param userId       用户
     * @param currencyCode 币种，默认 CNY
     * @param recycle      true = 查询前先做一次惰性回收（排障时能看到真实占用）
     */
    @GetMapping("/users/{userId}")
    public Map<String, Object> get(@PathVariable String userId,
                                   @RequestParam(name = "currencyCode", defaultValue = "CNY")
                                   String currencyCode,
                                   @RequestParam(name = "recycle", defaultValue = "false")
                                   boolean recycle) {
        LimitReserveService.requireUserAndCurrency(userId, currencyCode);
        if (recycle) {
            recycler.recycle(userId);
        }
        Instant now = Instant.now();
        LimitQueryService.LimitSnapshot snapshot = queryService.snapshot(userId, currencyCode, now);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("userId", snapshot.userId());
        response.put("currencyCode", snapshot.currencyCode());
        response.put("asOf", snapshot.asOf().toString());
        response.put("config", snapshot.config());
        response.put("periods", snapshot.periods());
        return response;
    }

    /**
     * 设置 / 更新限额（FR-020，幂等 upsert）。
     *
     * <p>body：{@code {"currencyCode":"CNY","dailyLimitMinor":15000,"monthlyLimitMinor":0,
     * "yearlyLimitMinor":0}}。{@code 0} = 该周期不限。</p>
     */
    @PutMapping("/users/{userId}")
    public ResponseEntity<Map<String, Object>> put(@PathVariable String userId,
                                                   @RequestBody SetLimitRequest request) {
        String currencyCode = request.currencyCode() == null || request.currencyCode().isBlank()
                ? "CNY" : request.currencyCode();
        LimitReserveService.requireUserAndCurrency(userId, currencyCode);

        long daily = requireNonNegative(request.dailyLimitMinor(), "dailyLimitMinor");
        long monthly = requireNonNegative(request.monthlyLimitMinor(), "monthlyLimitMinor");
        long yearly = requireNonNegative(request.yearlyLimitMinor(), "yearlyLimitMinor");

        UserPaymentLimit saved = limitRepository.upsert(new UserPaymentLimit(null, userId, currencyCode,
                daily, monthly, yearly,
                request.status() == null || request.status().isBlank()
                        ? UserPaymentLimit.STATUS_ACTIVE : request.status(),
                null));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", saved.userId());
        body.put("currencyCode", saved.currencyCode());
        body.put("dailyLimitMinor", saved.dailyLimitMinor());
        body.put("monthlyLimitMinor", saved.monthlyLimitMinor());
        body.put("yearlyLimitMinor", saved.yearlyLimitMinor());
        body.put("status", saved.status());
        // 顺手回带快照，前端一次调用即可刷新水位卡
        body.put("snapshot", queryService.snapshot(userId, currencyCode, Instant.now()).periods());
        return ResponseEntity.ok(body);
    }

    /**
     * 删除限额配置（FR-025：演示脚本清理用，避免污染后续场景）。
     *
     * <p>语义是「回归不限额」——删掉配置行后 {@code isLimited()} 恒 false，用户行为与
     * 今天逐字节一致（SC-010）。</p>
     */
    @org.springframework.web.bind.annotation.DeleteMapping("/users/{userId}")
    public Map<String, Object> delete(@PathVariable String userId,
                                      @RequestParam(name = "currencyCode", defaultValue = "CNY")
                                      String currencyCode) {
        LimitReserveService.requireUserAndCurrency(userId, currencyCode);
        boolean removed = limitRepository.delete(userId, currencyCode);
        return Map.of("userId", userId, "currencyCode", currencyCode, "removed", removed);
    }

    /** 某支付单的额度流水（排障 / 演示 trace 用）。 */
    @GetMapping("/payments/{paymentNo}/operations")
    public Map<String, Object> operations(@PathVariable String paymentNo) {
        List<Map<String, Object>> rows = queryService.operations(paymentNo).stream()
                .map(op -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("operationNo", op.operationNo());
                    row.put("opType", op.opType().name());
                    row.put("period", op.period().name());
                    row.put("amountMinor", op.amountMinor());
                    row.put("expiresAt", op.expiresAt() == null ? null : op.expiresAt().toString());
                    row.put("createdAt", op.createdAt() == null ? null : op.createdAt().toString());
                    return row;
                })
                .toList();
        return Map.of("paymentNo", paymentNo, "operations", rows);
    }

    /** 诊断：当前配置与待补偿规模（运维 / 演示用）。 */
    @GetMapping("/diagnostics")
    public Map<String, Object> diagnostics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("periods", List.of(LimitPeriod.values()).stream().map(Enum::name).toList());
        return result;
    }

    private static long requireNonNegative(Long value, String field) {
        if (value == null) {
            return 0L;
        }
        if (value < 0) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    field + " must be >= 0 (0 = no limit for that period); got " + value);
        }
        return value;
    }

    /** 设置限额请求（三个额度 0 = 不限）。 */
    public record SetLimitRequest(String currencyCode, Long dailyLimitMinor,
                                  Long monthlyLimitMinor, Long yearlyLimitMinor, String status) {
    }
}
