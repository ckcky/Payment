package com.payment.order.application;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;

/**
 * 单测桩事务管理器（spec 023 / M1 事务收窄配套）：无真实事务语义，段内代码直接执行——
 * in-memory repository 场景无需回滚能力，仅满足 {@code TransactionTemplate} 的编程接口。
 */
public final class NoopTransactionManager implements PlatformTransactionManager {

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
        return null; // 无真实事务：TransactionTemplate 回调拿到 null status 亦可正常执行
    }

    @Override
    public void commit(TransactionStatus status) throws TransactionException {
        // no-op
    }

    @Override
    public void rollback(TransactionStatus status) throws TransactionException {
        // no-op
    }
}
