package com.payment.ledger.domain.posting;

import com.payment.common.dto.rpc.AccountingEventType;
import com.payment.ledger.domain.AccountingEvent;

import java.util.List;

/**
 * 记账规则（spec 031 §6 / 原则 5/6）：一个 eventType 恰一条规则，代码 Strategy、
 * 无 DSL、无规则表、无热加载。<b>金额轧差、科目选择、借贷方向全部在规则内</b>——
 * 这是「规则定义会计意义」的落点，也是「决策权收口到账务域」的物理边界。
 */
public interface PostingRule {

    /** 本规则负责的事件类型。 */
    AccountingEventType eventType();

    /**
     * 把已确认财务事实展开成记账行。
     *
     * @param event    事件（槽位已由引擎按必填表校验）
     * @param original 原交易回查端口（ADJUSTMENT 红冲用；其余规则可忽略）
     */
    List<PostingLine> expand(AccountingEvent event, OriginalPostingLookup original);
}
