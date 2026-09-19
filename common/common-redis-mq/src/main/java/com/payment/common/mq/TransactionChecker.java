package com.payment.common.mq;

/**
 * 本地事务状态回查 SPI（spec 029 / FR-105 / ADR-0074 D6）。
 *
 * <p>半消息回查的真相来源：每个生产方为每种事件实现本接口，去**自己的库**查该事件对应的
 * 业务事实是否已落定（如 order 侧查 {@code orders.status} 是否 PAID、payment 侧查
 * {@code payments.status} 是否 SUCCEEDED）。</p>
 *
 * <p><b>为什么必须有</b>：进程在 prepare 与 commit 之间崩溃时，Redis 里只剩一条半消息，
 * 无法从消息本身判断本地事务是否已提交。唯一可靠的判据是数据库里的业务事实——这正是
 * 「本地事务先行」的现实含义（INV-3）。</p>
 */
public interface TransactionChecker {

    /** 回查结果。 */
    enum LocalTxState {
        /** 本地事务已成功落库 → 补投消息（commit）。 */
        COMMIT,
        /** 本地事务未成功（回滚或从未执行）→ 丢弃半消息。 */
        ROLLBACK,
        /** 暂时无法判定（如数据库不可达）→ 保留半消息下轮再查，超次进 DLQ。 */
        UNKNOWN
    }

    /**
     * 该 checker 是否处理此事件。
     *
     * @param envelope 待回查的半消息信封
     */
    boolean supports(EventEnvelope envelope);

    /**
     * 回查该事件对应的本地事务状态。
     *
     * <p>实现 MUST 只读、不得有副作用——回查可能被重复调用直至超次。</p>
     */
    LocalTxState check(EventEnvelope envelope);
}
