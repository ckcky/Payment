package com.payment.posting.application;

import com.payment.posting.domain.PendingPosting;

/**
 * 台账补投端口（spec 034 §12.1）：按事件类型把台账行的原始载荷重发到原目的地。
 *
 * <p>实现按 {@code eventType} 分派（infra 层持有真实出站客户端）：</p>
 * <ul>
 *   <li>记账类（PAYMENT_CAPTURE / REFUND / SETTLEMENT_MERCHANT / ADJUSTMENT）→ Ledger
 *       {@code POST /internal/ledger/accounting-events}（载荷即 AccountingEventRequest 原文；
 *       账本按 {eventType}:{sourceId} 派生键吸收重复，重放不双记——031 §9，台账可重试的前提）；</li>
 *   <li>通知类（ORDER_NOTIFY_SUCCEEDED / ORDER_NOTIFY_REFUND_RESULT）→ order 既有内部端点
 *       原请求重发（order 侧终态吸收 / 幂等，M7 重放 = 原请求重发）。</li>
 * </ul>
 *
 * <p><b>MUST NOT</b>：重放不得绕过消费端幂等（029 协议红线）、不得改写载荷、
 * 不得自行判定资金成败——只负责「把原请求再发一次」并如实回报结果。</p>
 */
public interface PostingReplayer {

    /**
     * 重投一行台账载荷。
     *
     * @return true = 目的地确认受理；false = 仍失败（调用方按失败记账）
     * @throws RuntimeException 目的地侧异常原样上抛（由调度器按失败处理，不吞）
     */
    boolean replay(PendingPosting posting);
}
