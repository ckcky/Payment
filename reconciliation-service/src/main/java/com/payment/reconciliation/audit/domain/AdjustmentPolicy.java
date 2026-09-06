package com.payment.reconciliation.audit.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;

import java.util.ArrayList;
import java.util.List;

/**
 * 调账硬规则校验与分录编排（FR-016 七条，纯函数；逐条有单测）：
 * ① 分录借贷平衡（由 ledger 聚合根强校验 + 本类构造保证）；
 * ② source_type=ADJUSTMENT / source_id=adjustNo / 幂等键 adjust:{adjustNo}；
 * ③ 不删改既有分录（只新增反向分录）；
 * ④ 累计调账额 ≤ 差异金额；
 * ⑤ operator + reason 必填；
 * ⑥ 双人复核（软约束，见 {@link #needsReview}）；
 * ⑦ 不动业务单据状态（调用方保证：本类只产出 ledger 记账计划）。
 */
public final class AdjustmentPolicy {

    /** 幂等键前缀（FR-016 ②）。 */
    public static final String IDEMPOTENCY_PREFIX = "adjust:";
    /** 双人复核金额阈值：> ¥100（10000 分）需复核（软约束 WARN 口径）。 */
    public static final long REVIEW_THRESHOLD_MINOR = 10_000L;

    private AdjustmentPolicy() {
    }

    /**
     * 单条记账分录计划。
     */
    public record PostingEntry(long accountId, String direction, long amountMinor) {
    }

    /**
     * 记账计划：一次调账 = 一条 ADJUSTMENT posting（内含红蓝字多分录也保持借贷平衡）。
     */
    public record PostingPlan(String idempotencyKey, List<PostingEntry> entries) {
    }

    /**
     * 双人复核软约束（plan §7.2 规则 6 / §11 ⑥）：
     * WRITE_OFF、金额 &gt; ¥100、缺 reviewer 或 operator==reviewer 时需要复核；
     * 调用方据配置决定 WARN 留痕（默认）或硬拒绝。
     */
    public static boolean needsReview(AuditAdjustmentKind kind, long amountMinor,
                                      String operator, String reviewer) {
        boolean reviewerMissing = reviewer == null || reviewer.isBlank();
        boolean samePerson = !reviewerMissing && reviewer.equals(operator);
        return kind == AuditAdjustmentKind.WRITE_OFF || amountMinor > REVIEW_THRESHOLD_MINOR
                || reviewerMissing || samePerson;
    }

    /**
     * 基础校验（⑤⑥⑦ + 金额合法性）；违规抛 {@code BizException}，不产生任何分录。
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
     * 生成记账计划（SC-008 / SC-011 / SC-013）。
     *
     * @param originalEntries 原分录（REVERSE / CORRECT 必须提供，SC-011 append-only 红冲）
     */
    public static PostingPlan buildPlan(String adjustNo, AuditAdjustmentKind kind,
                                        boolean underRecorded, long amountMinor,
                                        String targetAccountCode,
                                        List<PostingEntry> originalEntries) {
        List<PostingEntry> entries = new ArrayList<>();
        switch (kind) {
            case SUSPEND -> {
                if (underRecorded) {
                    entries.add(new PostingEntry(account(SuspensePolicy.CUSTOMER_CASH), "DEBIT", amountMinor));
                    entries.add(new PostingEntry(account(SuspensePolicy.SUSPENSE), "CREDIT", amountMinor));
                } else {
                    entries.add(new PostingEntry(account(SuspensePolicy.SUSPENSE), "DEBIT", amountMinor));
                    entries.add(new PostingEntry(account(SuspensePolicy.CUSTOMER_CASH), "CREDIT", amountMinor));
                }
            }
            case SUPPLEMENT -> {
                // 补记：借客户资金 / 贷应付商户（手续费 MVP 计 0；目标科目可覆盖）
                entries.add(new PostingEntry(account(SuspensePolicy.CUSTOMER_CASH), "DEBIT", amountMinor));
                entries.add(new PostingEntry(account(target(targetAccountCode)), "CREDIT", amountMinor));
            }
            case REVERSE -> {
                requireOriginal(originalEntries, kind);
                // 红冲：原分录完整反向（append-only，原分录不动）
                for (PostingEntry e : originalEntries) {
                    entries.add(new PostingEntry(e.accountId(), reverse(e.direction()), e.amountMinor()));
                }
            }
            case CORRECT -> {
                requireOriginal(originalEntries, kind);
                // 红蓝字：先反向冲原分录，再记正确金额（同一 posting 内借贷合计平衡）
                for (PostingEntry e : originalEntries) {
                    entries.add(new PostingEntry(e.accountId(), reverse(e.direction()), e.amountMinor()));
                }
                long debitTotal = originalEntries.stream()
                        .filter(e -> "DEBIT".equals(e.direction())).mapToLong(PostingEntry::amountMinor).sum();
                long creditTotal = originalEntries.stream()
                        .filter(e -> "CREDIT".equals(e.direction())).mapToLong(PostingEntry::amountMinor).sum();
                if (debitTotal > 0) {
                    entries.add(new PostingEntry(account(SuspensePolicy.CUSTOMER_CASH), "DEBIT", debitTotal));
                }
                if (creditTotal > 0) {
                    entries.add(new PostingEntry(account(target(targetAccountCode)), "CREDIT", creditTotal));
                }
            }
            case TRANSFER -> {
                // 从 SUSPENSE 转出：与挂账方向相反（挂账借资金/贷 SUSPENSE → 转出借 SUSPENSE/贷目标）
                if (underRecorded) {
                    entries.add(new PostingEntry(account(SuspensePolicy.SUSPENSE), "DEBIT", amountMinor));
                    entries.add(new PostingEntry(account(target(targetAccountCode)), "CREDIT", amountMinor));
                } else {
                    entries.add(new PostingEntry(account(target(targetAccountCode)), "DEBIT", amountMinor));
                    entries.add(new PostingEntry(account(SuspensePolicy.SUSPENSE), "CREDIT", amountMinor));
                }
            }
            case WRITE_OFF -> throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "WRITE_OFF disabled (audit.adjust.write-off.enabled=false)");
            default -> throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "unsupported kind: " + kind);
        }
        return new PostingPlan(IDEMPOTENCY_PREFIX + adjustNo, List.copyOf(entries));
    }

    private static void requireOriginal(List<PostingEntry> originalEntries, AuditAdjustmentKind kind) {
        if (originalEntries == null || originalEntries.isEmpty()) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    kind + " requires an original posting (none found)");
        }
    }

    private static String reverse(String direction) {
        return "DEBIT".equals(direction) ? "CREDIT" : "DEBIT";
    }

    private static String target(String targetAccountCode) {
        return targetAccountCode == null || targetAccountCode.isBlank()
                ? SuspensePolicy.MERCHANT_PAYABLE : targetAccountCode;
    }

    /** 科目码 → 预置科目 id（与 ledger Account 枚举 / 09-ledger-schema seed 对齐）。 */
    public static long account(String code) {
        return switch (code) {
            case SuspensePolicy.CUSTOMER_CASH -> 1L;
            case SuspensePolicy.MERCHANT_PAYABLE -> 2L;
            case SuspensePolicy.PLATFORM_FEE_REVENUE -> 3L;
            case SuspensePolicy.SETTLEMENT_PAYABLE -> 4L;
            case SuspensePolicy.SUSPENSE -> 5L;
            default -> throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "unknown account code: " + code);
        };
    }
}
