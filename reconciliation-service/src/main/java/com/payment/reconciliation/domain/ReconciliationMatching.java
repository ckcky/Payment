package com.payment.reconciliation.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 纯函数式匹配：平台事实与渠道账单逐笔比对，产出一致匹配与八类差异（spec 032 §8.2）。
 * 无副作用、无外部依赖，金额以 long 比较、状态以 String 比较。
 *
 * <p>两级匹配键（spec §8.1，降级可解释）：</p>
 * <ol>
 *   <li><b>强键</b> {@code (merchantId, referenceType, reference)}——双方都有商户维度；</li>
 *   <li><b>回退键</b> {@code (referenceType, reference)}——一方缺商户（不猜商户归属）；
 *       legacy 4 列账单（referenceType 空）退化为 {@code (reference)} 单键通道（历史语义兼容）。</li>
 * </ol>
 *
 * <p>弱匹配 {@code (merchantId, amountMinor)}（spec ③ 键去掉平台侧没有的 occurredAt）只出候选建议
 * （WARN 日志），<b>不改判</b>：差异仍为待处置态。比对顺序：{@code amountMinor → feeMinor → status}；
 * 平台侧无手续费引擎（推定 0），账单行 feeMinor &gt; 0 且本金一致 ⇒ {@code FEE_MISMATCH}（plan §2.6：
 * 只产差异，不补发 CHANNEL_FEE）。</p>
 *
 * <p>账单行分工：PAYMENT/REFUND 参与匹配；SETTLEMENT/FEE 不参与匹配（分别由 G3 渠道资金事实聚合消费）；
 * 无法归一行（缺商户/缺键/类型不可识别）⇒ {@code UNKNOWN_MAPPING}（吸收历史静默跳过，§11 #3）；
 * 同一 {@code (channelTxnNo, referenceType)} 重复 ⇒ {@code DUPLICATE_CHANNEL}（首条为准，不覆盖）。</p>
 *
 * <p>reference 为 null 的平台事实无法参与逐笔比对（spec 006 T019 / FR-018）：<b>绝不静默跳过</b>——
 * 逐条 WARN 并汇总带计数的 WARN。匹配语义不变。</p>
 */
public final class ReconciliationMatching {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationMatching.class);

    private ReconciliationMatching() {
    }

    /**
     * legacy 4 字段通道（spec 006 语义保留：单键 reference、金额→状态比对）。
     */
    public static ReconciliationMatchingResult match(List<PlatformFact> platformFacts,
                                                     List<ChannelStatement> channelStatements) {
        Map<String, PlatformFact> platforms = indexPlatform(safe(platformFacts));
        Map<String, ChannelStatement> channels = indexChannel(safe(channelStatements));

        TreeSet<String> refs = new TreeSet<>();
        refs.addAll(platforms.keySet());
        refs.addAll(channels.keySet());

        List<Match> matches = new ArrayList<>();
        List<Difference> differences = new ArrayList<>();

        for (String ref : refs) {
            PlatformFact p = platforms.get(ref);
            ChannelStatement c = channels.get(ref);
            if (p != null && c != null) {
                if (p.amountMinor() == c.amountMinor() && p.status().equals(c.status())) {
                    matches.add(new Match(ref, p.type(), p.amountMinor(), p.currencyCode()));
                } else if (p.amountMinor() != c.amountMinor()) {
                    differences.add(Difference.of(ref, DifferenceType.AMOUNT_MISMATCH,
                            p.amountMinor(), c.amountMinor(), p.status(), c.status()));
                } else {
                    differences.add(Difference.of(ref, DifferenceType.STATUS_MISMATCH,
                            p.amountMinor(), c.amountMinor(), p.status(), c.status()));
                }
            } else if (p != null) {
                differences.add(Difference.of(ref, DifferenceType.PLATFORM_ONLY,
                        p.amountMinor(), null, p.status(), null));
            } else {
                differences.add(Difference.of(ref, DifferenceType.CHANNEL_ONLY,
                        null, c.amountMinor(), null, c.status()));
            }
        }
        return new ReconciliationMatchingResult(matches, differences);
    }

    /**
     * 032 typed 通道：标准化账单行（StatementLine）× 平台已确认事实（PlatformFact）。
     * 三级降级匹配 + 差异八类（spec §8.1 / §8.2）。
     */
    public static ReconciliationMatchingResult matchTyped(List<PlatformFact> platformFacts,
                                                          List<StatementLineView> lines) {
        List<PlatformFact> facts = safe(platformFacts);
        List<StatementLineView> statementLines = safe(lines);

        List<Match> matches = new ArrayList<>();
        List<Difference> differences = new ArrayList<>();

        // 0) 无法归一行：UNKNOWN_MAPPING（不猜、不静默，spec §11 #3）。
        Set<Long> unmatchedLineIds = new HashSet<>();
        for (StatementLineView line : statementLines) {
            unmatchedLineIds.add(line.lineId());
        }
        for (StatementLineView line : statementLines) {
            String defect = line.normalizeDefect();
            if (defect != null) {
                unmatchedLineIds.remove(line.lineId());
                differences.add(Difference.typed(line.differenceReference(), DifferenceType.UNKNOWN_MAPPING,
                        null, line.amountMinor(), null, line.status(), line.merchantId(),
                        line.referenceType(), null));
                log.warn("statement line cannot be normalized: channel={} line={} defect={} ref={} raw={}",
                        line.channelCode(), line.lineNo(), defect, line.differenceReference(), line.rawText());
            }
        }
        // 0') 渠道重复行：同 (channelTxnNo, referenceType) 重复 ⇒ DUPLICATE_CHANNEL（首条为准，不覆盖）。
        Map<String, StatementLineView> firstByTxn = new HashMap<>();
        for (StatementLineView line : statementLines) {
            if (line.normalizeDefect() != null || line.channelTxnNo() == null || line.referenceType() == null) {
                continue;
            }
            String txnKey = line.channelTxnNo() + "|" + line.referenceType();
            StatementLineView first = firstByTxn.get(txnKey);
            if (first == null) {
                firstByTxn.put(txnKey, line);
            } else {
                unmatchedLineIds.remove(line.lineId());
                differences.add(Difference.typed(line.channelTxnNo(), DifferenceType.DUPLICATE_CHANNEL,
                        null, line.amountMinor(), null, line.status(), line.merchantId(),
                        line.referenceType(), null));
                log.warn("duplicate channel statement line: channel={} txnNo={} type={} firstLine={} dupLine={}",
                        line.channelCode(), line.channelTxnNo(), line.referenceType(),
                        first.lineNo(), line.lineNo());
            }
        }

        // 平台事实索引：强键 (merchantId, type, reference) 与回退键 (type, reference)。
        Map<String, PlatformFact> strongByPlatform = new LinkedHashMap<>();
        Map<String, PlatformFact> fallbackByPlatform = new LinkedHashMap<>();
        int noReferenceFacts = 0;
        for (PlatformFact fact : facts) {
            if (fact.reference() == null || fact.reference().isBlank()) {
                noReferenceFacts++;
                log.warn("platform fact without reference skipped (not comparable): type={} amountMinor={} status={}",
                        fact.type(), fact.amountMinor(), fact.status());
                continue;
            }
            if (fact.merchantId() != null && !fact.merchantId().isBlank()) {
                strongByPlatform.putIfAbsent(strongKey(fact.merchantId(), fact.type(), fact.reference()), fact);
            }
            fallbackByPlatform.putIfAbsent(fallbackKey(fact.type(), fact.reference()), fact);
        }
        warnSkipped("platform", noReferenceFacts);

        // 1) 强键匹配；2) 回退键匹配（一方缺商户，不猜归属）。
        Set<PlatformFact> matchedFacts = new HashSet<>();
        for (StatementLineView line : new ArrayList<>(statementLines)) {
            if (!unmatchedLineIds.contains(line.lineId()) || line.normalizeDefect() != null) {
                continue; // 缺陷行/重复行已出差异，不再参与匹配
            }
            PlatformFact fact = null;
            if (line.merchantId() != null && !line.merchantId().isBlank()
                    && line.referenceType() != null) {
                fact = strongByPlatform.get(strongKey(line.merchantId(), line.referenceType(), line.reference()));
            }
            boolean strong = fact != null;
            if (!strong) {
                // 回退：一方缺商户。legacy 行（referenceType 空）退化为 reference 单键。
                fact = line.referenceType() == null
                        ? fallbackByPlatform.get(legacyKey(line.reference()))
                        : fallbackByPlatform.get(fallbackKey(line.referenceType(), line.reference()));
            }
            if (fact == null || matchedFacts.contains(fact)) {
                continue;
            }
            unmatchedLineIds.remove(line.lineId());
            matchedFacts.add(fact);
            compare(matches, differences, fact, line, strong);
        }

        // 3) 剩余单侧差异 + 弱匹配候选建议（只建议、不改判）。
        for (PlatformFact fact : facts) {
            if (fact.reference() == null || fact.reference().isBlank() || matchedFacts.contains(fact)) {
                continue;
            }
            differences.add(Difference.typed(fact.reference(), DifferenceType.PLATFORM_ONLY,
                    fact.amountMinor(), null, fact.status(), null, fact.merchantId(), fact.type(), null));
        }
        for (StatementLineView line : statementLines) {
            if (!unmatchedLineIds.contains(line.lineId())) {
                continue;
            }
            unmatchedLineIds.remove(line.lineId());
            differences.add(Difference.typed(line.differenceReference(), DifferenceType.CHANNEL_ONLY,
                    null, line.amountMinor(), null, line.status(), line.merchantId(),
                    line.referenceType(), null));
            suggestCandidate(facts, line);
        }
        return new ReconciliationMatchingResult(matches, differences);
    }

    /** 比对顺序：amount → fee → status（spec §8.1）。 */
    private static void compare(List<Match> matches, List<Difference> differences,
                                PlatformFact fact, StatementLineView line, boolean strong) {
        if (fact.amountMinor() != line.amountMinor()) {
            differences.add(Difference.typed(fact.reference(), DifferenceType.AMOUNT_MISMATCH,
                    fact.amountMinor(), line.amountMinor(), fact.status(), line.status(),
                    fact.merchantId() != null ? fact.merchantId() : line.merchantId(),
                    line.referenceType() != null ? line.referenceType() : fact.type(), null));
            return;
        }
        if (line.feeMinor() > 0) {
            // 平台侧无手续费引擎（推定 0）：本金一致而渠道手续费 > 0 ⇒ FEE_MISMATCH（走 ADJUSTMENT 收口）。
            differences.add(Difference.typed(fact.reference(), DifferenceType.FEE_MISMATCH,
                    fact.amountMinor(), line.amountMinor(), fact.status(), line.status(),
                    fact.merchantId() != null ? fact.merchantId() : line.merchantId(),
                    line.referenceType() != null ? line.referenceType() : fact.type(), line.feeMinor()));
            return;
        }
        if (!fact.status().equals(line.status())) {
            differences.add(Difference.typed(fact.reference(), DifferenceType.STATUS_MISMATCH,
                    fact.amountMinor(), line.amountMinor(), fact.status(), line.status(),
                    fact.merchantId() != null ? fact.merchantId() : line.merchantId(),
                    line.referenceType() != null ? line.referenceType() : fact.type(), null));
            return;
        }
        matches.add(new Match(fact.reference(), fact.type(), fact.amountMinor(), fact.currencyCode()));
    }

    /** 弱匹配③：只出候选建议（WARN），不改判（差异仍待处置，处置记录可写候选依据）。 */
    private static void suggestCandidate(List<PlatformFact> facts, StatementLineView line) {
        if (line.merchantId() == null || line.merchantId().isBlank()) {
            return;
        }
        List<String> candidates = facts.stream()
                .filter(f -> line.merchantId().equals(f.merchantId()) && f.amountMinor() == line.amountMinor())
                .map(PlatformFact::reference)
                .limit(3)
                .toList();
        if (!candidates.isEmpty()) {
            log.warn("weak-match candidate (advisory only, difference stays pending): lineRef={} candidates={}",
                    line.differenceReference(), candidates);
        }
    }

    private static String strongKey(String merchantId, String type, String reference) {
        return merchantId + "|" + type + "|" + reference;
    }

    private static String fallbackKey(String type, String reference) {
        return type + "|" + reference;
    }

    private static String legacyKey(String reference) {
        return "LEGACY|" + reference;
    }

    private static Map<String, PlatformFact> indexPlatform(List<PlatformFact> facts) {
        Map<String, PlatformFact> map = new LinkedHashMap<>();
        int skipped = 0;
        for (PlatformFact f : facts) {
            if (f.reference() != null) {
                map.put(f.reference(), f);
            } else {
                skipped++;
                log.warn("platform fact without reference skipped: type={} amountMinor={} status={}",
                        f.type(), f.amountMinor(), f.status());
            }
        }
        warnSkipped("platform", skipped);
        return map;
    }

    private static Map<String, ChannelStatement> indexChannel(List<ChannelStatement> statements) {
        Map<String, ChannelStatement> map = new LinkedHashMap<>();
        int skipped = 0;
        for (ChannelStatement s : statements) {
            if (s.reference() != null) {
                map.put(s.reference(), s);
            } else {
                skipped++;
                log.warn("channel statement without reference skipped: amountMinor={} status={}",
                        s.amountMinor(), s.status());
            }
        }
        warnSkipped("channel", skipped);
        return map;
    }

    private static void warnSkipped(String side, int skipped) {
        if (skipped > 0) {
            log.warn("reconciliation skipped {} record(s) without reference (not comparable)", side, skipped);
        }
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }

    /**
     * 匹配层账单行视图：解耦 {@code statement.StatementLine}（聚合实体）与匹配纯函数——
     * lineId 供匹配过程中的行去重/状态跟踪（实现侧传 statementLines 列表下标或 DB id）。
     */
    public interface StatementLineView {
        long lineId();

        int lineNo();

        String channelCode();

        String channelTxnNo();

        String referenceType();

        String reference();

        String merchantId();

        long amountMinor();

        long feeMinor();

        String status();

        String currency();

        String rawText();

        /** 归一缺陷原因（null = 无缺陷）。 */
        String normalizeDefect();

        /** 差异引用口径：优先 reference，其次 channelTxnNo，兜底行号。 */
        String differenceReference();
    }
}
