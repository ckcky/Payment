package com.payment.reconciliation.audit.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.dto.rpc.AccountCode;
import com.payment.common.dto.rpc.AccountingEventType;
import com.payment.common.dto.rpc.AccountingSourceType;

/**
 * 调账硬规则校验与 ADJUSTMENT 事件编排（FR-016 七条，纯函数；逐条有单测）：
 * ① 分录借贷平衡由账本展开期强校验（本类只声明「哪一类、多少、从哪到哪、冲销哪笔」）；
 * ② source_type=RECONCILIATION / source_id=adjustNo，幂等键由账本按
 *   {@code ADJUSTMENT:{adjustNo}} 派生（spec 031 §6.2 原则 10——本地不再拼 "adjust:" 前缀）；
 * ③ 不删改既有分录（红冲由账本读原交易取反，append-only）；
 * ④ 累计调账额 ≤ 差异金额（调用方判定）；
 * ⑤ operator + reason 必填；
 * ⑥ 双人复核（软约束，见 {@link #needsReview}）；
 * ⑦ 不动业务单据状态（本类只产出事件计划）。
 *
 * <p>spec 031 §9：audit 域只声明转账语义（§7.6），**不再本地拼装方向/科目 id**——
 * 借贷方向由账本 {@code AdjustmentRule} 按「Dr to / Cr from」恒等推导（冲突 1 已裁决），
 * 红冲（REVERSE/CORRECT）由账本按 {@code reversesEventType:reversesSourceId} 读原交易取反。</p>
 */
public final class AdjustmentPolicy {

    /** 双人复核金额阈值：> ¥100（10000 分）需复核（软约束 WARN 口径）。 */
    public static final long REVIEW_THRESHOLD_MINOR = 10_000L;

    private AdjustmentPolicy() {
    }

    /** 被冲销的原事件引用（difference 的 sourceType/sourceId，账本据此回查原交易）。 */
    public record OriginalRef(String sourceType, String sourceId) {
    }

    /**
     * ADJUSTMENT 事件计划：转账语义三类带 from/to；REVERSE 只带红冲引用；
     * CORRECT 红冲 + 补记正确额（同一事件）。
     */
    public record AdjustPlan(String adjustmentKind, long amountMinor,
                             String fromAccountCode, String toAccountCode,
                             String reversesEventType, String reversesSourceId) {
    }

    /**
     * 双人复核软约束（plan §7.2 规则 6 / §11 ⑥）：
     * WRITE_OFF、金额 &gt; ¥100、缺 reviewer 或 operator==reviewer 时需要复核。
     */
    public static boolean needsReview(AuditAdjustmentKind kind, long amountMinor,
                                      String operator, String reviewer) {
        boolean reviewerMissing = reviewer == null || reviewer.isBlank();
        boolean samePerson = !reviewerMissing && reviewer.equals(operator);
        return kind == AuditAdjustmentKind.WRITE_OFF || amountMinor > REVIEW_THRESHOLD_MINOR
                || reviewerMissing || samePerson;
    }

    /**
     * 基础校验（⑤⑥⑦ + 金额合法性）；违规抛 {@code BizException}，不产生任何事件。
     */
    public static void validate(AuditAdjustmentKind kind, long amountMinor,
                                String operator, String reviewer, String reason,
                                boolean writeOffEnabled, boolean enforceDoubleCheck) {
        if (amountMinor <= 0) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "adjust amount must be > 0");
        }
        if (operator == null || operator.isBlank()) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "operator is required");
        }
        if (reason == null || reason.isBlank()) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "reason is required");
        }
        if (kind == AuditAdjustmentKind.WRITE_OFF && !writeOffEnabled) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "WRITE_OFF disabled (audit.adjust.write-off.enabled=false)");
        }
        if (enforceDoubleCheck && needsReview(kind, amountMinor, operator, reviewer)) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "double-check review required (reviewer missing / same as operator / large amount)");
        }
    }

    /**
     * 生成 ADJUSTMENT 事件计划（SC-008 / SC-011 / SC-013）。
     *
     * <p>方向口径（与 017 现行模板逐行等价，现金腿按冲突 2 裁决 CUSTOMER_CASH→BANK_CASH）：
     * 「Dr to / Cr from」——挂账账少记 = 借资金/贷 SUSPENSE ⇒ to=资金、from=SUSPENSE。</p>
     *
     * @param original REVERSE / CORRECT 必须提供被冲销原事件引用（SC-011 append-only 红冲）
     */
    public static AdjustPlan buildPlan(AuditAdjustmentKind kind, boolean underRecorded, long amountMinor,
                                       String targetAccountCode, OriginalRef original) {
        String bankCash = AccountCode.BANK_CASH.name();
        String suspense = AccountCode.SUSPENSE.name();
        return switch (kind) {
            case SUSPEND -> underRecorded
                    ? transfer(kind, amountMinor, suspense, bankCash)
                    : transfer(kind, amountMinor, bankCash, suspense);
            case SUPPLEMENT ->
                    // 补记：借资金 / 贷目标（手续费 MVP 计 0；目标科目可覆盖）
                    transfer(kind, amountMinor, target(targetAccountCode), bankCash);
            case REVERSE -> {
                requireOriginal(original, kind);
                yield new AdjustPlan(kind.name(), amountMinor, null, null,
                        eventOf(original.sourceType()), original.sourceId());
            }
            case CORRECT -> {
                // 红蓝字：红冲引用 + 正确金额的转账语义（借资金/贷目标），同一事件内由账本合并
                requireOriginal(original, kind);
                AdjustPlan base = transfer(kind, amountMinor, target(targetAccountCode), bankCash);
                yield new AdjustPlan(base.adjustmentKind(), base.amountMinor(), base.fromAccountCode(),
                        base.toAccountCode(), eventOf(original.sourceType()), original.sourceId());
            }
            case TRANSFER -> {
                // 从 SUSPENSE 转出/转入：与挂账方向相反（挂账 to=资金/from=SUSPENSE ⇒ 转出 to=SUSPENSE…见下）
                String tgt = target(targetAccountCode);
                yield underRecorded
                        ? transfer(kind, amountMinor, tgt, suspense)
                        : transfer(kind, amountMinor, suspense, tgt);
            }
            case WRITE_OFF -> throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "WRITE_OFF disabled (audit.adjust.write-off.enabled=false)");
            default -> throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "unsupported kind: " + kind);
        };
    }

    /** kind → 账本 adjustmentKind 字符串直通（枚举名一致）。 */
    private static AdjustPlan transfer(AuditAdjustmentKind kind, long amount, String from, String to) {
        return new AdjustPlan(kind.name(), amount, from, to, null, null);
    }

    /** 来源域 → 被冲销事件类型（difference.sourceType 即账本 sourceType 口径）。 */
    private static String eventOf(String sourceType) {
        return switch (sourceType) {
            case "PAYMENT" -> AccountingEventType.PAYMENT_CAPTURE.name();
            case "REFUND" -> AccountingEventType.REFUND.name();
            case "SETTLEMENT" -> AccountingEventType.MERCHANT_SETTLEMENT.name();
            default -> throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "no reversible event for sourceType: " + sourceType);
        };
    }

    /** 目标科目码归一：缺省 = MERCHANT_PAYABLE；旧码 PLATFORM_FEE_REVENUE 就地更名 FEE_REVENUE（ADR-0078）。 */
    private static String target(String targetAccountCode) {
        if (targetAccountCode == null || targetAccountCode.isBlank()) {
            return AccountCode.MERCHANT_PAYABLE.name();
        }
        String code = "PLATFORM_FEE_REVENUE".equals(targetAccountCode)
                ? AccountCode.FEE_REVENUE.name() : targetAccountCode;
        try {
            AccountCode.valueOf(code);
        } catch (IllegalArgumentException e) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "unknown account code: " + code);
        }
        return code;
    }

    private static void requireOriginal(OriginalRef original, AuditAdjustmentKind kind) {
        if (original == null || original.sourceType() == null || original.sourceId() == null) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    kind + " requires an original posting reference (none found)");
        }
    }
}
