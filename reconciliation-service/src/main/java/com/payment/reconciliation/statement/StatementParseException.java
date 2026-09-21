package com.payment.reconciliation.statement;

/**
 * 账单结构性解析失败（spec 032 §11 #2）：整批 REJECTED + 行号 + 原因，不产生半套差异。
 */
public class StatementParseException extends RuntimeException {

    private final int lineNo;
    private final String reason;

    public StatementParseException(int lineNo, String reason) {
        super("statement parse failed at line " + lineNo + ": " + reason);
        this.lineNo = lineNo;
        this.reason = reason;
    }

    public int getLineNo() {
        return lineNo;
    }

    public String getReason() {
        return reason;
    }
}
