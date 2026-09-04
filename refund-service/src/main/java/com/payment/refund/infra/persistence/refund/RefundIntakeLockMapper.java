package com.payment.refund.infra.persistence.refund;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 退款受理悲观锁 Mapper（H1）：以 {@code payment_id} 为主键的排他行锁。
 *
 * <p>{@code INSERT ... ON DUPLICATE KEY UPDATE payment_no = payment_no} 语义为「确保行存在并持有其行锁」：
 * 首次受理插入锁行，并发受理则阻塞于该行的唯一键冲突，直至持锁事务提交/回滚，从而串行化同一支付下的
 * 累计退款金额读改写，杜绝超退款竞态。</p>
 */
public interface RefundIntakeLockMapper {

    @Insert("INSERT INTO refund_intake_locks (payment_no) VALUES (#{paymentNo}) " +
            "ON DUPLICATE KEY UPDATE payment_no = payment_no")
    int lockForIntake(@Param("paymentNo") String paymentNo);
}
