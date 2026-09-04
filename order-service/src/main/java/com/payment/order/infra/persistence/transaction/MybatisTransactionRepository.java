package com.payment.order.infra.persistence.transaction;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.order.domain.Transaction;
import com.payment.order.domain.TransactionRepository;
import com.payment.order.domain.TransactionStatus;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 交易仓储 MyBatis 实现（T045b）：交易 1:1 订单，按订单号查询；更新走乐观锁。
 */
@Repository
public class MybatisTransactionRepository implements TransactionRepository {

    private final TransactionMapper transactionMapper;

    public MybatisTransactionRepository(TransactionMapper transactionMapper) {
        this.transactionMapper = transactionMapper;
    }

    @Override
    public Optional<Transaction> findById(Long id) {
        TransactionEntity entity = transactionMapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public Optional<Transaction> findByOrderId(String orderId) {
        TransactionEntity entity = transactionMapper.selectOne(
                Wrappers.<TransactionEntity>lambdaQuery().eq(TransactionEntity::getOrderId, orderId));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public Transaction save(Transaction transaction) {
        if (transaction.getId() == null) {
            TransactionEntity entity = toEntity(transaction);
            transactionMapper.insert(entity);
            transaction.setId(entity.getId());
            transaction.setVersion(entity.getVersion());
            return transaction;
        }
        TransactionEntity entity = toEntity(transaction);
        if (transactionMapper.updateById(entity) == 0) {
            throw BizException.of(ErrorCodes.CONFLICT, "transaction concurrent update: " + transaction.getId());
        }
        transaction.setVersion(transaction.getVersion() + 1);
        return transaction;
    }

    private Transaction toDomain(TransactionEntity entity) {
        return Transaction.rehydrate(entity.getId(), entity.getTransactionNo(), entity.getOrderId(), entity.getAmountMinor(),
                entity.getCurrencyCode(), entity.getPurpose(), TransactionStatus.valueOf(entity.getStatus()),
                entity.getVersion());
    }

    private TransactionEntity toEntity(Transaction transaction) {
        TransactionEntity entity = new TransactionEntity();
        entity.setId(transaction.getId());
        entity.setTransactionNo(transaction.getTransactionNo());
        entity.setOrderId(transaction.getOrderId());
        entity.setAmountMinor(transaction.getAmountMinor());
        entity.setCurrencyCode(transaction.getCurrencyCode());
        entity.setPurpose(transaction.getPurpose());
        entity.setStatus(transaction.getStatus().name());
        entity.setVersion(transaction.getVersion());
        return entity;
    }
}
