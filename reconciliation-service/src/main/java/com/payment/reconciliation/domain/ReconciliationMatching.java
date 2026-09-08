package com.payment.reconciliation.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 纯函数式匹配：平台事实与渠道账单按 reference 逐笔比对，产出一致匹配与四类差异。
 * 无副作用、无外部依赖，金额以 long 比较、状态以 String 比较。
 *
 * <p>reference 为 null 的记录无法参与逐笔比对（spec 006 T019 / FR-018）：<b>绝不静默跳过</b>——
 * 逐条 WARN 并汇总一条带计数的 WARN，便于资金运营发现上游事实缺号。匹配语义不变。</p>
 */
public final class ReconciliationMatching {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationMatching.class);

    private ReconciliationMatching() {
    }

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
}
