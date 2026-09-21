package com.payment.reconciliation.statement;

import java.util.List;

/**
 * 账单解析端口（spec 032 §5 方案 B）：账单上传 + 落盘 + 解析归一收口在 reconciliation 内，
 * 格式实现按渠道演进（当前 CSV：v2 双表头 / legacy 4 列），不引入渠道 SDK。
 */
public interface StatementParser {

    /**
     * 解析账单内容为标准化行。
     *
     * @throws StatementParseException 结构性错误（表头不识别 / 列数不足 / 金额非数字 / 币种非法）
     *                                 ——整批 REJECTED，不产生半套差异（§11 #2）
     */
    List<StatementLine> parse(String channelCode, String period, String content);
}
