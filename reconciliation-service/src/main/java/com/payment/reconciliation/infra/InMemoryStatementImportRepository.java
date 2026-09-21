package com.payment.reconciliation.infra;

import com.payment.reconciliation.statement.StatementImport;
import com.payment.reconciliation.statement.StatementImportRepository;
import com.payment.reconciliation.statement.StatementLine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存账单导入仓储：仅用于领域/编排单测（不走 Spring 注入），生产由 MyBatis 实现承接。
 */
public class InMemoryStatementImportRepository implements StatementImportRepository {

    private final Map<Long, StatementImport> byId = new ConcurrentHashMap<>();
    private final Map<String, Long> byNo = new ConcurrentHashMap<>();
    private final Map<Long, List<StatementLine>> linesByImport = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong();

    @Override
    public StatementImport save(StatementImport imprt) {
        if (imprt.getId() == null) {
            imprt.setId(idGen.incrementAndGet());
        }
        byId.put(imprt.getId(), imprt);
        byNo.put(imprt.getImportNo(), imprt.getId());
        return imprt;
    }

    @Override
    public void saveLines(Long importId, String channelCode, List<StatementLine> lines) {
        List<StatementLine> withImport = lines.stream()
                .map(l -> l.importId() == null
                        ? new StatementLine(importId, l.lineNo(), l.channelCode(), l.channelTxnNo(),
                                l.referenceType(), l.reference(), l.referenceKind(), l.merchantId(),
                                l.amountMinor(), l.feeMinor(), l.currency(), l.status(), l.occurredAt(), l.rawText())
                        : l)
                .toList();
        linesByImport.merge(importId, new ArrayList<>(withImport), (a, b) -> {
            a.addAll(b);
            return a;
        });
    }

    @Override
    public Optional<StatementImport> findById(Long id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<StatementImport> findByNo(String importNo) {
        return Optional.ofNullable(byNo.get(importNo)).map(byId::get);
    }

    @Override
    public Optional<StatementImport> findByIdentity(String channelCode, String period, String contentFingerprint) {
        return byId.values().stream()
                .filter(i -> channelCode.equals(i.getChannelCode())
                        && period.equals(i.getPeriod())
                        && contentFingerprint.equals(i.getContentFingerprint()))
                .findFirst();
    }

    @Override
    public Optional<StatementImport> findLatestNormalized(String period, String channelCode) {
        return byId.values().stream()
                .filter(i -> period.equals(i.getPeriod()))
                .filter(i -> channelCode == null || channelCode.equals(i.getChannelCode()))
                .filter(StatementImport::normalized)
                .max(Comparator.comparing(StatementImport::getId));
    }

    @Override
    public List<StatementImport> list(String period, String channelCode) {
        return byId.values().stream()
                .filter(i -> period == null || period.equals(i.getPeriod()))
                .filter(i -> channelCode == null || channelCode.equals(i.getChannelCode()))
                .sorted(Comparator.comparing(StatementImport::getId).reversed())
                .toList();
    }

    @Override
    public List<StatementLine> findLines(Long importId) {
        return List.copyOf(linesByImport.getOrDefault(importId, List.of()));
    }
}
