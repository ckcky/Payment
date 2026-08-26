package com.payment.reconciliation.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 纯函数式匹配：平台事实与渠道账单按 reference 逐笔比对，产出一致匹配与四类差异。
 * 无副作用、无外部依赖，金额以 long 比较、状态以 String 比较。
 */
public final class ReconciliationMatching {

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
        for (PlatformFact f : facts) {
            if (f.reference() != null) {
                map.put(f.reference(), f);
            }
        }
        return map;
    }

    private static Map<String, ChannelStatement> indexChannel(List<ChannelStatement> statements) {
        Map<String, ChannelStatement> map = new LinkedHashMap<>();
        for (ChannelStatement s : statements) {
            if (s.reference() != null) {
                map.put(s.reference(), s);
            }
        }
        return map;
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
