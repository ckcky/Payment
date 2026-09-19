package com.payment.payment.limit.infra.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.payment.payment.limit.domain.UserPaymentLimit;
import com.payment.payment.limit.domain.UserPaymentLimitRepository;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 限额配置仓储 MyBatis 实现（幂等 upsert，spec 027 / FR-020）。 */
@Repository
public class MybatisUserPaymentLimitRepository implements UserPaymentLimitRepository {

    private final UserPaymentLimitMapper mapper;

    public MybatisUserPaymentLimitRepository(UserPaymentLimitMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<UserPaymentLimit> find(String userId, String currencyCode) {
        UserPaymentLimitEntity entity = mapper.selectOne(Wrappers.<UserPaymentLimitEntity>lambdaQuery()
                .eq(UserPaymentLimitEntity::getUserId, userId)
                .eq(UserPaymentLimitEntity::getCurrencyCode, currencyCode));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /**
     * 幂等 upsert：先按 {@code UK(user_id, currency_code)} 回查。
     *
     * <p>不用 {@code ON DUPLICATE KEY UPDATE} 是因为本表带乐观锁 {@code version}，
     * 更新走 MyBatis-Plus 的 {@code updateById} 才能让版本号按既有约定递增；
     * 而本表的并发压力来自「运维改配置」，不是资金路径，回查+更新的成本可接受。</p>
     */
    @Override
    public UserPaymentLimit upsert(UserPaymentLimit limit) {
        UserPaymentLimitEntity existing = mapper.selectOne(Wrappers.<UserPaymentLimitEntity>lambdaQuery()
                .eq(UserPaymentLimitEntity::getUserId, limit.userId())
                .eq(UserPaymentLimitEntity::getCurrencyCode, limit.currencyCode()));
        if (existing == null) {
            UserPaymentLimitEntity entity = new UserPaymentLimitEntity();
            entity.setUserId(limit.userId());
            entity.setCurrencyCode(limit.currencyCode());
            entity.setDailyLimitMinor(limit.dailyLimitMinor());
            entity.setMonthlyLimitMinor(limit.monthlyLimitMinor());
            entity.setYearlyLimitMinor(limit.yearlyLimitMinor());
            entity.setStatus(limit.status() == null ? UserPaymentLimit.STATUS_ACTIVE : limit.status());
            mapper.insert(entity);
            return toDomain(entity);
        }
        existing.setDailyLimitMinor(limit.dailyLimitMinor());
        existing.setMonthlyLimitMinor(limit.monthlyLimitMinor());
        existing.setYearlyLimitMinor(limit.yearlyLimitMinor());
        if (limit.status() != null) {
            existing.setStatus(limit.status());
        }
        mapper.updateById(existing);
        return toDomain(existing);
    }

    @Override
    public boolean delete(String userId, String currencyCode) {
        return mapper.delete(Wrappers.<UserPaymentLimitEntity>lambdaQuery()
                .eq(UserPaymentLimitEntity::getUserId, userId)
                .eq(UserPaymentLimitEntity::getCurrencyCode, currencyCode)) > 0;
    }

    private UserPaymentLimit toDomain(UserPaymentLimitEntity e) {
        return new UserPaymentLimit(e.getId(), e.getUserId(), e.getCurrencyCode(),
                nz(e.getDailyLimitMinor()), nz(e.getMonthlyLimitMinor()), nz(e.getYearlyLimitMinor()),
                e.getStatus(), e.getVersion());
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
