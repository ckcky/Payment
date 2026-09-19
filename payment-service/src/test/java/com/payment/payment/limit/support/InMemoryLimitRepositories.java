package com.payment.payment.limit.support;

import com.payment.payment.limit.domain.LimitOperation;
import com.payment.payment.limit.domain.LimitOperationRepository;
import com.payment.payment.limit.domain.LimitOperationType;
import com.payment.payment.limit.domain.LimitPeriod;
import com.payment.payment.limit.domain.LimitUsage;
import com.payment.payment.limit.domain.LimitUsageRepository;
import com.payment.payment.limit.domain.UserPaymentLimit;
import com.payment.payment.limit.domain.UserPaymentLimitRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 限额三仓储的内存实现（spec 027 测试用）。
 *
 * <p><b>为什么要有内存实现</b>：本 spec 的核心不变量全是<b>并发与幂等语义</b>
 * （原子 UPDATE 的 0 行语义、UK 撞键跳过、软超限可见）。这些用 H2 集成测试覆盖成本高、跑得慢；
 * 内存实现能把「原子性」用 {@code synchronized} 精确建模，让单测直接压语义。
 * 真实 SQL 的正确性由 {@code LimitPersistenceIT}（H2）单独兜底——两层各管一段，
 * 不重复也不留空。</p>
 *
 * <p><b>关键：这里刻意模拟「原子 UPDATE」的语义</b>——方法内部 <b>先判后改且不可分割</b>，
 * 且 {@code reserveIfWithinLimit} 用 {@code used + pending + a <= limit} 判定后返回 0/1，
 * 与 SQL 的「影响行数」同义。若实现里写成「调用方先查再改」，本实现反而会掩盖竞态——
 * 故这里把判定收进方法的原子块内，忠实反映真实实现契约。</p>
 */
public final class InMemoryLimitRepositories {

    private InMemoryLimitRepositories() {
    }

    /** 内存限额配置仓储。 */
    public static final class Limits implements UserPaymentLimitRepository {
        private final Map<String, UserPaymentLimit> store = new HashMap<>();

        @Override
        public synchronized Optional<UserPaymentLimit> find(String userId, String currencyCode) {
            return Optional.ofNullable(store.get(key(userId, currencyCode)));
        }

        @Override
        public synchronized UserPaymentLimit upsert(UserPaymentLimit limit) {
            store.put(key(limit.userId(), limit.currencyCode()), limit);
            return limit;
        }

        @Override
        public synchronized boolean delete(String userId, String currencyCode) {
            return store.remove(key(userId, currencyCode)) != null;
        }

        private static String key(String userId, String currencyCode) {
            return userId + "|" + currencyCode;
        }
    }

    /** 内存占用仓储：把「原子 UPDATE」建模为同步块内的判改一体。 */
    public static final class Usages implements LimitUsageRepository {
        private final Map<String, LimitUsage> store = new HashMap<>();

        @Override
        public synchronized Optional<LimitUsage> find(String userId, String currencyCode, LimitPeriod period) {
            return Optional.ofNullable(store.get(key(userId, currencyCode, period)));
        }

        @Override
        public synchronized List<LimitUsage> findAll(String userId, String currencyCode) {
            List<LimitUsage> out = new ArrayList<>();
            for (LimitUsage u : store.values()) {
                if (u.userId().equals(userId) && u.currencyCode().equals(currencyCode)) {
                    out.add(u);
                }
            }
            out.sort(Comparator.comparing(u -> u.period().ordinal()));
            return out;
        }

        @Override
        public synchronized void ensureRow(String userId, String currencyCode, LimitPeriod period,
                                           LocalDate periodStart) {
            String k = key(userId, currencyCode, period);
            LimitUsage existing = store.get(k);
            if (existing == null) {
                store.put(k, new LimitUsage(null, userId, currencyCode, period, periodStart, 0L, 0L, 1));
                return;
            }
            // 跨周期：period_start 变了就重置该周期的累计（D10 现算语义）。
            if (!existing.periodStart().equals(periodStart)) {
                store.put(k, new LimitUsage(existing.id(), userId, currencyCode, period, periodStart,
                        0L, 0L, existing.version()));
            }
        }

        @Override
        public synchronized int reserveIfWithinLimit(String userId, String currencyCode,
                                                     LimitPeriod period, long amountMinor, long limitMinor) {
            LimitUsage u = store.get(key(userId, currencyCode, period));
            if (u == null) {
                return 0;
            }
            if (u.usedMinor() + u.pendingMinor() + amountMinor > limitMinor) {
                return 0;   // 超限：不改动任何字段（与 SQL WHERE 不匹配时 0 行同义）
            }
            store.put(key(userId, currencyCode, period), new LimitUsage(u.id(), u.userId(),
                    u.currencyCode(), u.period(), u.periodStart(), u.usedMinor(),
                    u.pendingMinor() + amountMinor, u.version()));
            return 1;
        }

        @Override
        public synchronized int confirm(String userId, String currencyCode, LimitPeriod period,
                                        long amountMinor) {
            LimitUsage u = store.get(key(userId, currencyCode, period));
            if (u == null) {
                return 0;
            }
            // 无条件累加（D12 软超限）：不做 clamp。
            store.put(key(userId, currencyCode, period), new LimitUsage(u.id(), u.userId(),
                    u.currencyCode(), u.period(), u.periodStart(), u.usedMinor() + amountMinor,
                    Math.max(0L, u.pendingMinor() - amountMinor), u.version()));
            return 1;
        }

        @Override
        public synchronized int release(String userId, String currencyCode, LimitPeriod period,
                                        long amountMinor) {
            LimitUsage u = store.get(key(userId, currencyCode, period));
            if (u == null) {
                return 0;
            }
            // 下限保护（INV-7 / D6）：绝不把 pending 减成负数。
            store.put(key(userId, currencyCode, period), new LimitUsage(u.id(), u.userId(),
                    u.currencyCode(), u.period(), u.periodStart(), u.usedMinor(),
                    Math.max(0L, u.pendingMinor() - amountMinor), u.version()));
            return 1;
        }

        /** 测试直读：把金额写到期望状态（模拟「历史已发生」）。 */
        public synchronized void seed(String userId, String currencyCode, LimitPeriod period,
                                      long periodStartEpochDay, long usedMinor, long pendingMinor) {
            store.put(key(userId, currencyCode, period), new LimitUsage(null, userId, currencyCode,
                    period, LocalDate.ofEpochDay(periodStartEpochDay), usedMinor, pendingMinor, 1));
        }

        private static String key(String userId, String currencyCode, LimitPeriod period) {
            return userId + "|" + currencyCode + "|" + period;
        }
    }

    /** 内存流水仓储：UK(bizNo, opType, period) 的幂等语义在 {@code insert} 里如实建模。 */
    public static final class Operations implements LimitOperationRepository {
        private final List<LimitOperation> store = new ArrayList<>();

        @Override
        public synchronized boolean insert(LimitOperation operation) {
            // UK = (biz_no, op_type, period)：一笔支付在**每个周期**上每种操作各允许一条。
            // 漏掉 period 会让第一个周期的 RESERVE 把月/年周期一并判为「已执行」——
            // 那正是限额静默失效的成因，故这里必须与 DDL 严格同构。
            boolean dup = store.stream().anyMatch(o -> o.bizNo().equals(operation.bizNo())
                    && o.opType() == operation.opType()
                    && o.period() == operation.period());
            if (dup) {
                return false;   // 撞 UK：未插入
            }
            store.add(new LimitOperation((long) (store.size() + 1), operation.operationNo(),
                    operation.bizNo(), operation.opType(), operation.userId(),
                    operation.currencyCode(), operation.period(), operation.amountMinor(),
                    operation.expiresAt(), operation.createdAt()));
            return true;
        }

        @Override
        public synchronized boolean exists(String bizNo, LimitOperationType opType, LimitPeriod period) {
            return store.stream().anyMatch(o -> o.bizNo().equals(bizNo)
                    && o.opType() == opType
                    && (period == null || o.period() == period));
        }

        @Override
        public synchronized List<LimitOperation> findByBizNo(String bizNo) {
            List<LimitOperation> out = new ArrayList<>();
            for (LimitOperation o : store) {
                if (o.bizNo().equals(bizNo)) {
                    out.add(o);
                }
            }
            out.sort(Comparator.comparing(LimitOperation::id));
            return out;
        }

        @Override
        public synchronized Optional<LimitOperation> find(String bizNo, LimitOperationType opType,
                                                          LimitPeriod period) {
            return store.stream()
                    .filter(o -> o.bizNo().equals(bizNo) && o.opType() == opType
                            && (period == null || o.period() == period))
                    .findFirst();
        }

        @Override
        public synchronized void delete(LimitOperation operation) {
            store.removeIf(o -> o.bizNo().equals(operation.bizNo())
                    && o.opType() == operation.opType()
                    && (operation.period() == null || o.period() == operation.period()));
        }

        @Override
        public synchronized List<LimitOperation> findUnsettledReserves(String userId) {
            List<LimitOperation> out = new ArrayList<>();
            for (LimitOperation o : store) {
                if (!o.userId().equals(userId) || o.opType() != LimitOperationType.RESERVE) {
                    continue;
                }
                boolean settled = store.stream().anyMatch(t -> t.bizNo().equals(o.bizNo())
                        && t.opType().isTerminal());
                if (!settled) {
                    out.add(o);
                }
            }
            out.sort(Comparator.comparing(LimitOperation::id));
            return out;
        }

        /** 测试直读：全部流水。 */
        public synchronized List<LimitOperation> all() {
            return new ArrayList<>(store);
        }
    }
}
