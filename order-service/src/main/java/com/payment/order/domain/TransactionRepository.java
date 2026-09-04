package com.payment.order.domain;

import java.util.Optional;

/**
 * 交易仓储边界（领域接口）。MVP 中一个订单只有一个交易（1:1）。
 */
public interface TransactionRepository {

    Optional<Transaction> findById(Long id);

    Optional<Transaction> findByOrderNo(String orderNo);

    Transaction save(Transaction transaction);
}
