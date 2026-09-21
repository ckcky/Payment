package com.payment.reconciliation.infra;

import com.payment.reconciliation.domain.ReconciliationBatch;
import com.payment.reconciliation.domain.ReconciliationDifference;
import com.payment.reconciliation.domain.ReconciliationRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 内存对账批次仓储：仅用于领域/编排单测（不走 Spring 注入），生产由 MyBatis 实现承接。
 *
 * <p>差异台账语义与生产一致：撞 uk_diff_identity（同 period+kind+referenceType+reference）
 * 返回既有行（幂等吸收）。</p>
 */
public class InMemoryReconciliationRepository implements ReconciliationRepository {

    private final Map<Long, ReconciliationBatch> byId = new ConcurrentHashMap<>();
    private final Map<Long, ReconciliationDifference> differencesById = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong();
    private final AtomicLong diffIdGen = new AtomicLong();

    @Override
    public Optional<ReconciliationBatch> findById(Long id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<ReconciliationBatch> findByPeriod(String period) {
        return byId.values().stream()
                .filter(b -> period.equals(b.getPeriod()))
                .max(Comparator.comparing(ReconciliationBatch::getId));
    }

    @Override
    public Optional<ReconciliationBatch> findByPeriodAndImport(String period, String channelCode, Long importId) {
        return byId.values().stream()
                .filter(b -> period.equals(b.getPeriod()))
                .filter(b -> b.getChannelCode().equals(channelCode))
                .filter(b -> importId == null ? b.getImportId() == null : importId.equals(b.getImportId()))
                .findFirst();
    }

    @Override
    public List<ReconciliationBatch> findByPeriodBetween(String from, String to) {
        return byId.values().stream()
                .filter(b -> b.getPeriod().compareTo(from) >= 0 && b.getPeriod().compareTo(to) <= 0)
                .toList();
    }

    @Override
    public ReconciliationBatch save(ReconciliationBatch batch) {
        if (batch.getId() == null) {
            batch.setId(idGen.incrementAndGet());
        }
        byId.put(batch.getId(), batch);
        return batch;
    }

    @Override
    public ReconciliationDifference insertDifference(ReconciliationDifference difference) {
        Optional<ReconciliationDifference> existing = differencesById.values().stream()
                .filter(d -> d.getPeriod().equals(difference.getPeriod()))
                .filter(d -> d.getKind() == difference.getKind())
                .filter(d -> matches(d.getReferenceType(), difference.getReferenceType()))
                .filter(d -> matches(d.getReference(), difference.getReference()))
                .findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        if (difference.getId() == null) {
            difference.setId(diffIdGen.incrementAndGet());
        }
        differencesById.put(difference.getId(), difference);
        return difference;
    }

    @Override
    public Optional<ReconciliationDifference> findDifferenceByNo(String diffNo) {
        return differencesById.values().stream()
                .filter(d -> d.getDiffNo().equals(diffNo))
                .findFirst();
    }

    @Override
    public ReconciliationDifference updateDifference(ReconciliationDifference difference) {
        differencesById.put(difference.getId(), difference);
        return difference;
    }

    @Override
    public List<ReconciliationDifference> findDifferencesByBatch(Long batchId) {
        return differencesById.values().stream()
                .filter(d -> batchId.equals(d.getBatchId()))
                .sorted(Comparator.comparing(ReconciliationDifference::getId))
                .collect(Collectors.toList());
    }

    @Override
    public DifferencePage findDifferences(String period, String status, String merchantId, String kind,
                                          int page, int size) {
        List<ReconciliationDifference> filtered = differencesById.values().stream()
                .filter(d -> period == null || period.equals(d.getPeriod()))
                .filter(d -> status == null || status.equals(d.getStatus().name()))
                .filter(d -> merchantId == null || merchantId.equals(d.getMerchantId()))
                .filter(d -> kind == null || kind.equals(d.getKind().name()))
                .sorted(Comparator.comparing(ReconciliationDifference::getId).reversed())
                .toList();
        int from = Math.min(page * size, filtered.size());
        int to = Math.min(from + size, filtered.size());
        return new DifferencePage(List.copyOf(filtered.subList(from, to)), filtered.size());
    }

    private static boolean matches(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}
