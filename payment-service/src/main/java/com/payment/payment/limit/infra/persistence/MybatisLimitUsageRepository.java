package com.payment.payment.limit.infra.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.payment.payment.limit.domain.LimitPeriod;
import com.payment.payment.limit.domain.LimitUsage;
import com.payment.payment.limit.domain.LimitUsageRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 周期额度占用仓储 MyBatis 实现（spec 027 / ADR-0071 D3）。
 *
 * <p><b>所有金额变更都是单条原子 SQL</b>（{@link UserLimitUsageMapper}），本类不做
 * 「读-改-写」——那正是并发穿透的成因。返回影响行数供上层判定语义成败。</p>
 */
@Repository
public class MybatisLimitUsageRepository implements LimitUsageRepository {

    private final UserLimitUsageMapper mapper;

    public MybatisLimitUsageRepository(UserLimitUsageMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<LimitUsage> find(String userId, String currencyCode, LimitPeriod period) {
        UserLimitUsageEntity entity = mapper.selectOne(Wrappers.<UserLimitUsageEntity>lambdaQuery()
                .eq(UserLimitUsageEntity::getUserId, userId)
                .eq(UserLimitUsageEntity::getCurrencyCode, currencyCode)
                .eq(UserLimitUsageEntity::getPeriod, period.name()));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public List<LimitUsage> findAll(String userId, String currencyCode) {
        return mapper.selectList(Wrappers.<UserLimitUsageEntity>lambdaQuery()
                        .eq(UserLimitUsageEntity::getUserId, userId)
                        .eq(UserLimitUsageEntity::getCurrencyCode, currencyCode))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public void ensureRow(String userId, String currencyCode, LimitPeriod period, LocalDate periodStart) {
        mapper.ensureRow(userId, currencyCode, period.name(), periodStart);
    }

    @Override
    public int reserveIfWithinLimit(String userId, String currencyCode, LimitPeriod period,
                                    long amountMinor, long limitMinor) {
        // periodStart 现算（D10）：跨周期时旧行不匹配 → 0 行 → 上层 ensureRow 后重试
        LocalDate periodStart = period.periodStart(java.time.Instant.now());
        return mapper.reserveIfWithinLimit(userId, currencyCode, period.name(), periodStart,
                amountMinor, limitMinor);
    }

    @Override
    public int confirm(String userId, String currencyCode, LimitPeriod period, long amountMinor) {
        return mapper.confirm(userId, currencyCode, period.name(), amountMinor);
    }

    @Override
    public int release(String userId, String currencyCode, LimitPeriod period, long amountMinor) {
        return mapper.release(userId, currencyCode, period.name(), amountMinor);
    }

    private LimitUsage toDomain(UserLimitUsageEntity e) {
        return new LimitUsage(e.getId(), e.getUserId(), e.getCurrencyCode(),
                LimitPeriod.parse(e.getPeriod()), e.getPeriodStart(),
                nz(e.getUsedMinor()), nz(e.getPendingMinor()), e.getVersion());
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
