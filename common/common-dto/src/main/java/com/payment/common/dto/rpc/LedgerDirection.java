package com.payment.common.dto.rpc;

/**
 * 分录方向契约枚举（spec 031 §7.7 门禁）：科目 code 常量与 direction 字面量只允许出现在
 * ledger 实现与**事件契约枚举**中——上游调用方（只读回显解析等）一律引用本枚举，
 * 不得散落 "DEBIT" / "CREDIT" 字符串字面量。
 */
public enum LedgerDirection {
    DEBIT,
    CREDIT
}
