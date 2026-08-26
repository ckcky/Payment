package com.payment.common.core.idempotency;

import java.util.Optional;

/**
 * 幂等登记边界：按业务作用域和幂等键记录首次结果，重复请求返回原结果。
 *
 * <p>这是一个最小能力边界（plan T012），不是通用幂等框架。各服务在本地持久化中实现
 * 真正的事务性幂等（数据库唯一约束兜底），本接口只定义共享语义。</p>
 */
public interface IdempotencyRegistry {

    /**
     * 原子地记录首次结果。
     *
     * @return {@code true} 表示首次记录成功；{@code false} 表示该键已存在（未被覆盖）。
     */
    boolean recordIfAbsent(IdempotencyKey key, String result);

    /** 返回已记录的结果引用（若存在）。 */
    Optional<String> find(IdempotencyKey key);
}
