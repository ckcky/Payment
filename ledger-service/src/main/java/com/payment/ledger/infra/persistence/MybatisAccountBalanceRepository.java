package com.payment.ledger.infra.persistence;

import com.payment.ledger.domain.AccountBalance;
import com.payment.ledger.domain.AccountBalanceRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 余额投影仓储 MyBatis 实现（读侧 + 重建）。写入侧累加在
 * {@code MybatisLedgerRepository#save} 的同一事务内完成（§10 构造性保证）。
 */
@Repository
public class MybatisAccountBalanceRepository implements AccountBalanceRepository {

    private final AccountBalanceMapper mapper;

    public MybatisAccountBalanceRepository(AccountBalanceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<AccountBalance> find(long accountInstanceId, String currency) {
        return Optional.ofNullable(mapper.findByKey(accountInstanceId, currency)).map(this::toDomain);
    }

    @Override
    public List<AccountBalance> findAll() {
        return mapper.findAll().stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional
    public int rebuild() {
        // 全量重算：清空后按分录聚合回填（低频管理路径，非在线记账路径）
        mapper.deleteAll();
        return mapper.rebuildFromEntries();
    }

    private AccountBalance toDomain(AccountBalanceEntity entity) {
        return new AccountBalance(entity.getAccountInstanceId(), entity.getCurrency(),
                entity.getDebitTotal(), entity.getCreditTotal(), entity.getEntryCount(),
                entity.getLastEntryId());
    }
}
