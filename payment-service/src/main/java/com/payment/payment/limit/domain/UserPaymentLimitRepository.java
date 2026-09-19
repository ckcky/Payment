package com.payment.payment.limit.domain;

import java.util.Optional;

/**
 * 限额配置仓储（spec 027 / FR-001~003）。
 *
 * <p>「无行 = 不限额」（FR-012）：{@link #find} 返回空是<b>正常路径</b>，不是错误。</p>
 */
public interface UserPaymentLimitRepository {

    /** 按用户 + 币种取配置；无行返回空（= 不限额，FR-012）。 */
    Optional<UserPaymentLimit> find(String userId, String currencyCode);

    /** 幂等 upsert（FR-020）：存在则更新三个额度与状态，不存在则插入。返回落库后的配置。 */
    UserPaymentLimit upsert(UserPaymentLimit limit);

    /**
     * 删除配置（FR-025：演示脚本清理用）。
     *
     * <p>语义是「回归不限额」：删掉行后 {@code isLimited()} 恒 false，用户行为与
     * 引入限额之前逐字节一致（SC-010）。</p>
     *
     * @return {@code true} = 确有行被删除
     */
    boolean delete(String userId, String currencyCode);
}
