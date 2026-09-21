package com.payment.ledger.infra.persistence;

import com.payment.ledger.domain.LedgerPeriod;
import com.payment.ledger.domain.LedgerPeriodRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/**
 * 会计期间仓储 MyBatis 实现：close = 先 UPDATE，无行则 INSERT（撞键再 UPDATE，幂等）。
 */
@Repository
public class MybatisLedgerPeriodRepository implements LedgerPeriodRepository {

    private final LedgerPeriodMapper mapper;

    public MybatisLedgerPeriodRepository(LedgerPeriodMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<LedgerPeriod> find(String period, String currency) {
        return Optional.ofNullable(mapper.findByKey(period, currency)).map(this::toDomain);
    }

    @Override
    public List<LedgerPeriod> findAll() {
        return mapper.findAll().stream().map(this::toDomain).toList();
    }

    @Override
    public void close(String period, String currency, String closedBy) {
        Instant now = Instant.now();
        int updated = mapper.markClosed(period, currency, now, closedBy);
        if (updated == 0) {
            try {
                mapper.insertClosed(period, currency, now, closedBy);
            } catch (DuplicateKeyException e) {
                mapper.markClosed(period, currency, now, closedBy);
            }
        }
    }

    private LedgerPeriod toDomain(LedgerPeriodEntity entity) {
        return new LedgerPeriod(entity.getPeriod(), entity.getCurrency(),
                LedgerPeriod.Status.valueOf(entity.getStatus()), entity.getClosedAt(),
                entity.getClosedBy());
    }
}
