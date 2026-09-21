package com.payment.arch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 高基数政策门禁（spec 035 §7 / ADR-0083 决策 2，HC-1~HC-3 的机器可检查部分）。
 *
 * <h3>为什么是静态扫描</h3>
 * 今天 93 个业务指标的标签键<b>碰巧</b>只有低基数维度，但没有任何条文或检查守住——
 * 下一个人给 counter 加 {@code paymentNo} 标签不会被拦住（spec §1.4）。本测试把「政策」变成
 * 「红即拦」：扫描全仓 {@code src/main/java} 中 {@code metrics.counter/timer/gauge(...)}
 * 调用（{@code BusinessMetrics} 端口，资金域全部埋点均经此端口），解析成对出现的标签键/值。
 * {@code MeterRegistry} 运行期遍历断言（HC-4 机制）由 test-infra 的
 * {@code MetricsAssert.assertTagValuesWithinAllowedSet} 提供，随各服务 L2a 用例复用。
 *
 * <h3>规则</h3>
 * <ul>
 *   <li><b>键白名单</b>：标签键 MUST ∈ 有界维度集合（spec §1.4 实测 16 键 + 031/032/034 与
 *       035 目录新增的 {@code eventType/channel/caller/currency/channelCode/window/policy}）；
 *       新键 = 先在指标目录登记值域（HC-3）再扩白名单。</li>
 *   <li><b>HC-2 禁令</b>：标签键 MUST NOT 以 {@code No}/{@code Id} 结尾（业务单号 / 实体 ID）；
 *       标签<b>值</b>表达式 MUST NOT 是 {@code *No}/{@code *Id}/{@code *Uuid}/{@code *Timestamp}
 *       变量或 {@code get*No()}/{@code get*Id()} 取值调用（字符串字面量不受限——字面量即可穷举值）。</li>
 *   <li><b>period 例外条款</b>（spec §6.1）：{@code YYYY-MM} 型 {@code period} 只允许出现在
 *       {@code gauge}。文本扫描无法判型，落地为两条：<b>a)</b> 值为字面量 {@code YYYY-MM} 形态或
 *       {@code getPeriod()}/{@code getPeriodKey()} 调用的标签值一律红（防新增）；<b>b)</b> 限额子域
 *       {@code LimitPeriod} <b>窗口枚举</b>（DAILY/…，非账期）以 {@code period.name()} 上 counter 属
 *       有界值，不在射程——该用法以基线数量棘轮守卫（超出登记数即红，防默默扩散）。</li>
 * </ul>
 *
 * <h3>防空转</h3>
 * 阳性对照：扫描 MUST 实际找到 ≥90 个指标调用与已知键（如 {@code module}、{@code bucket}、
 * 已登记的 period-counter 站点），否则说明解析器失效而非仓库干净。
 */
class MetricsCardinalityTest {

    /** 仓库根（模块目录 = deployment/architecture-tests）。 */
    private static final Path REPO_ROOT = Paths.get("..", "..");

    /** 扫描范围：与 {@link RpcEdgeAllowListTest} 同一服务清单 + common 模块 + mock 渠道演示件。 */
    private static final String[] SOURCE_ROOTS = {
            "merchant-service", "catalog-service", "order-service", "payment-service",
            "fulfillment-service", "entitlement-service", "reconciliation-service",
            "settlement-service", "ledger-service",
            "common/common-core", "common/common-redis-mq",
            "deployment/mock-channel-web",
    };

    /** 标签键有界白名单（HC-1/HC-3：新键先进指标目录登记值域，再进本集合）。 */
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "module", "reason", "op", "result", "topic", "group", "source", "phase",
            "kind", "cause", "target", "status", "state", "scope", "bucket", "period",
            "periods", "routed", "eventType", "channel", "channelCode", "caller",
            "currency", "window", "policy", "orderStatus", "severity");

    /**
     * 基线棘轮：counter/timer 上带 {@code period} 标签的既有站点数（限额子域窗口枚举 10 处
     * + 032 对账/审计账期 3 处）。HC 例外条款判定「Gauge-only」，但政策守增量不追历史（M-1），
     * 故对非 Gauge 的 period 使用取 ≤ 基线：新增即红，删除后应下调基线常量。
     */
    private static final int PERIOD_ON_NON_GAUGE_BASELINE = 13;

    /** HC-2：键名禁令——业务单号 / 实体 ID 不得成为标签键。 */
    private static final Pattern FORBIDDEN_KEY = Pattern.compile("(No|Id)$");

    /** HC-2 值禁令 ①：JavaBean 取号/取 ID/取账期访问器（如 {@code payment.getPaymentNo()}）。 */
    private static final Pattern FORBIDDEN_GETTER_VALUE =
            Pattern.compile("\\bget[A-Z][A-Za-z0-9_]*(?:No|Id|Uuid|Timestamp|Period)\\(\\)");

    /** HC-2 值禁令 ②：九个业务 ID + 关联键的 record 式访问器（spec §6.1 禁入 label 清单）。 */
    private static final Pattern FORBIDDEN_ACCESSOR_VALUE = Pattern.compile(
            "\\b(?:bizNo|paymentNo|orderNo|transactionNo|refundNo|channelRefundNo|channelRequestId"
                    + "|channelReference|ledgerTransactionNo|reconciliationId|settlementNo|batchNo"
                    + "|msgId|merchantId|userId|idempotencyKey|traceId)\\(\\)");

    /** HC-2 值禁令 ③：以 No/Id/Uuid/Timestamp 结尾的裸变量（如 {@code paymentNo}、{@code merchantId}）。 */
    private static final Pattern FORBIDDEN_VARIABLE_VALUE =
            Pattern.compile("\\b[a-z][A-Za-z0-9_]*(?:No|Id|Uuid|Timestamp)\\b");

    /** YYYY-MM 字面量（账期值直接写死在标签里同样禁）。 */
    private static final Pattern YEAR_MONTH_LITERAL = Pattern.compile("\"\\d{4}-\\d{2}\"");

    private record Site(String file, int line, String metric, String kind, String key, String value) {
        @Override
        public String toString() {
            return file + ":" + line + " " + kind + "(" + metric + ") tag \"" + key + "\"=" + value;
        }
    }

    private static final Pattern CALL = Pattern.compile("\\bmetrics\\.(counter|timer|gauge)\\s*\\(");

    @org.junit.jupiter.api.Test
    void metricCallTagsRespectHighCardinalityPolicy() throws IOException {
        List<String[]> calls = new ArrayList<>(); // {root-relative path, kind, argsText, firstLine}
        int scannedFiles = 0;
        for (String root : SOURCE_ROOTS) {
            Path src = REPO_ROOT.resolve(root).resolve("src/main/java");
            if (!Files.isDirectory(src)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(src)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    scannedFiles++;
                    String text = Files.readString(f, StandardCharsets.UTF_8);
                    var matcher = CALL.matcher(text);
                    while (matcher.find()) {
                        int open = matcher.end() - 1;
                        int close = matchBalanced(text, open);
                        if (close < 0) {
                            continue;
                        }
                        calls.add(new String[]{
                                REPO_ROOT.relativize(f).toString(),
                                matcher.group(1),
                                text.substring(open + 1, close),
                                String.valueOf(text.substring(0, matcher.start()).split("\n", -1).length),
                        });
                    }
                }
            }
        }

        // 阳性对照（防空转）：解析器必须真的看到足量调用与已知键。
        assertThat(calls.size())
                .as("扫描到的 metrics.* 调用数（解析器失效保护，阳性对照）")
                .isGreaterThanOrEqualTo(90);

        Set<String> seenKeys = new TreeSet<>();
        List<Site> violations = new ArrayList<>();
        int periodOnNonGauge = 0;
        for (String[] call : calls) {
            List<String> args = splitArgs(call[2]);
            if (args.isEmpty()) {
                continue;
            }
            String metric = strip(first(args));
            String kind = call[1];
            // counter/timer: name, value, tags...;  gauge: name, supplier, tags...
            int tagStart = "gauge".equals(kind) ? 2 : 2;
            if (args.size() <= tagStart) {
                continue;
            }
            for (int i = tagStart; i + 1 < args.size(); i += 2) {
                String keyArg = args.get(i).trim();
                String value = args.get(i + 1).trim();
                if (!keyArg.startsWith("\"")) {
                    violations.add(new Site(call[0], Integer.parseInt(call[3]), metric, kind,
                            "<dynamic-key>", keyArg));
                    continue;
                }
                String key = strip(keyArg);
                seenKeys.add(key);
                if (FORBIDDEN_KEY.matcher(key).find()) {
                    violations.add(new Site(call[0], Integer.parseInt(call[3]), metric, kind, key, value));
                } else if (!ALLOWED_KEYS.contains(key)) {
                    violations.add(new Site(call[0], Integer.parseInt(call[3]), metric, kind,
                            key + " <not-in-allowlist>", value));
                }
                String exprValue = value.replaceAll("\"[^\"]*\"", " ");
                boolean forbidden = FORBIDDEN_GETTER_VALUE.matcher(exprValue).find()
                        || FORBIDDEN_ACCESSOR_VALUE.matcher(exprValue).find()
                        || FORBIDDEN_VARIABLE_VALUE.matcher(exprValue).find();
                if (forbidden) {
                    violations.add(new Site(call[0], Integer.parseInt(call[3]), metric, kind, key, value));
                }
                if ("period".equals(key) && !"gauge".equals(kind)) {
                    periodOnNonGauge++;
                }
                if (YEAR_MONTH_LITERAL.matcher(value).find()) {
                    violations.add(new Site(call[0], Integer.parseInt(call[3]), metric, kind, key, value));
                }
            }
        }

        assertThat(seenKeys).as("键白名单覆盖既有全部维度").contains("module", "result", "bucket");
        assertThat(periodOnNonGauge)
                .as("period 上非 Gauge 的基线棘轮（HC 例外条款：新增须先过目录评审并下调本常量）")
                .isLessThanOrEqualTo(PERIOD_ON_NON_GAUGE_BASELINE);
        assertThat(violations)
                .as("HC-1/HC-2 违规（标签键越白名单 / 单号-ID-账期入标签）")
                .isEmpty();
    }

    private static String first(List<String> args) {
        return args.get(0);
    }

    private static String strip(String literal) {
        String s = literal.trim();
        int end = s.lastIndexOf('"');
        int begin = s.indexOf('"');
        return begin >= 0 && end > begin ? s.substring(begin + 1, end) : s;
    }

    /** 从 open 处 '(' 找到配对的 ')'（字符串字面量内的括号与转义不计）。 */
    private static int matchBalanced(String text, int open) {
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\') {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 按顶层逗号切分实参（嵌套括号 / 字符串字面量内的逗号不切）。 */
    private static List<String> splitArgs(String argsText) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < argsText.length(); i++) {
            char c = argsText.charAt(i);
            if (escape) {
                escape = false;
                cur.append(c);
                continue;
            }
            if (c == '\\') {
                escape = true;
                cur.append(c);
                continue;
            }
            if (c == '"') {
                inString = !inString;
            }
            if (!inString) {
                if (c == '(' || c == '[' || c == '{') {
                    depth++;
                } else if (c == ')' || c == ']' || c == '}') {
                    depth--;
                } else if (c == ',' && depth == 0) {
                    parts.add(cur.toString());
                    cur.setLength(0);
                    continue;
                }
            }
            cur.append(c);
        }
        if (!cur.toString().isBlank()) {
            parts.add(cur.toString());
        }
        return parts;
    }
}
