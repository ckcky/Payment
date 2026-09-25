package com.payment.arch;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运行时 RPC 边允许清单门禁（spec 033 §7 / ADR-0081 决策 3，R-A 方案）。
 *
 * <h3>为什么需要这条门禁</h3>
 * {@link ServiceBoundaryTest} 的全部规则都是<b>字节码静态依赖</b>断言：服务之间不 import 对方的类。
 * 但跨服务通信走的是 Feign（运行时 HTTP），一个 {@code @FeignClient} 接口对 ArchUnit 的
 * 「编译期依赖」规则完全不可见——因此 {@code order ↔ payment} 的运行时调用环（backlog #4 /
 * design-review M10）在既有门禁下是<b>隐形的</b>。本测试把每个 {@code @FeignClient} 接口静态抽取为
 *
 * <pre>caller -&gt; target [clientFqcn]</pre>
 *
 * 三元组，并与进 git 的基线 {@code rpc-edges.txt} <b>逐行全等</b>比对——规则形态是「允许清单」而非
 * 「禁止环检测」：新增/删除任何一条边都会红，强制在 review 里回答「这条边该不该有」。
 * 该文件同时是 {@code technical-solution.md} 调用图的事实来源（spec 033 §7 副产品）。
 *
 * <h3>边与端点的口径</h3>
 * <ul>
 *   <li><b>caller</b> = 物理拥有该接口的 Maven 模块（{@code com.payment.payment..} 属 payment-service，
 *       015/P3 合并，故其出站边记在 payment 名下——与部署单元一致，与「服务」口径对齐）；</li>
 *   <li><b>target</b> = 注解 {@code name} 属性去掉 {@code -service} 后缀，映射到九服务之一；
 *       出现未知目标即红（新目标 = 新边 = 必须讨论）；</li>
 *   <li><b>[clientFqcn]</b> = 客户端接口全限定名。同一 (caller, target) 下新增<b>方法</b>不改基线——
 *       接口形状由 L3 契约快照（{@code InternalApiSnapshotTest}）守卫，两层各司其职；新增<b>客户端类
 *       / 边</b>必改基线。</li>
 * </ul>
 *
 * <h3>防空转</h3>
 * 静态抽取若因包名/注解名写错而得到空集，与「真的检查过」无法区分。故先做阳性对照：
 * 必须真的发现 {@code @FeignClient} 接口、且已知环 {@code order -> payment} 必须在结果中
 * （该环是当前代码的事实，backlog #4 在案；它出现在允许清单里正是「显式接受并可见」的体现）。
 *
 * <h3>附带的封口规则（ADR-0081 决策 1）</h3>
 * Testcontainers 放宽仅限<b>测试作用域</b>。各服务 {@code target/classes} 即其 {@code src/main} 产物，
 * 依赖若渗入生产代码这里必然现形——用一条否定式规则把口子封死，生产依赖树零变化才可审计。
 */
class RpcEdgeAllowListTest {

    private static final String FEIGN_CLIENT_ANNOTATION = "org.springframework.cloud.openfeign.FeignClient";
    private static final String BASELINE_RESOURCE = "/rpc-edges.txt";

    /** 与 {@link ServiceBoundaryTest} 同一服务清单（含 015/P3：refund 并入 payment-service）。 */
    private static final String[] SERVICES = {
            "merchant", "catalog", "order", "payment",
            "fulfillment", "entitlement", "reconciliation", "settlement", "ledger"
    };

    /** caller 口径修正：包前缀属于合并进该服务的域（015/P3）。 */
    private static final Map<String, String> PACKAGE_PREFIX_TO_SERVICE = Map.of("refund", "payment");

    /** service -> 该服务 target/classes 导入的字节码（分服务导入以获得物理归属）。 */
    private static final Map<String, JavaClasses> CLASSES_BY_SERVICE = new HashMap<>();

    @BeforeAll
    static void importAllServices() {
        for (String service : SERVICES) {
            Path classes = Paths.get("..", "..", service + "-service", "target", "classes");
            if (classes.toFile().isDirectory()) {
                CLASSES_BY_SERVICE.put(service, new ClassFileImporter().importPaths(classes));
            }
        }
    }

    // ---------- 门禁本体 ----------

    @Test
    void runtimeRpcEdgesMustEqualTheAllowListBaseline() {
        List<String> actual = extractRpcEdges();

        // 阳性对照（防空转）：抽取机制必须真的发现了 Feign 客户端与已知环
        long clientCount = actual.size();
        assertThat(clientCount)
                .as("@FeignClient 接口抽取数（为 0 说明注解名/导入路径失效，门禁将空转假绿）")
                .isGreaterThanOrEqualTo(10L);
        assertThat(actual)
                .as("已知运行时环 order -> payment（backlog #4 / design-review M10）必须可见；"
                        + "它从抽取结果中消失说明静态抽取已失效")
                .anySatisfy(line -> assertThat(line).startsWith("order -> payment ["));

        List<String> expected = readBaseline();
        assertThat(actual)
                .as("运行时 RPC 边必须逐行等于允许清单基线 rpc-edges.txt（spec 033 §7：新增边 MUST 在 "
                        + "PR 描述中说明「为何需要新边」；上方为基线，下方为实际抽取结果）")
                .isEqualTo(expected);
    }

    @Test
    void srcMainMustNotDependOnTestcontainers() {
        for (Map.Entry<String, JavaClasses> entry : CLASSES_BY_SERVICE.entrySet()) {
            ArchRule rule = noClasses()
                    .should().dependOnClassesThat().resideInAPackage("org.testcontainers..")
                    .because("ADR-0081 决策 1：Testcontainers 放宽仅限测试作用域（test scope），"
                            + "生产依赖树零变化；src/main 一旦依赖 org.testcontainers，"
                            + "「仅测试用」的口子即被突破（spec 033 §5.1 推荐裁决方向）");
            rule.check(entry.getValue());
        }
    }

    // ---------- 抽取与基线 ----------

    /**
     * 三元组抽取：按<b>物理模块</b>定位 caller（ refund 域的客户端记在 payment 名下），
     * target 取注解 name/value 属性。排序后输出，保证逐字节确定性。
     */
    private static List<String> extractRpcEdges() {
        List<String> edges = new ArrayList<>();
        for (Map.Entry<String, JavaClasses> entry : CLASSES_BY_SERVICE.entrySet()) {
            String caller = entry.getKey();
            for (JavaClass candidate : entry.getValue()) {
                Optional<JavaAnnotation<JavaClass>> feign = candidate.getAnnotations().stream()
                        .filter(a -> a.getType().getName().equals(FEIGN_CLIENT_ANNOTATION))
                        .findFirst();
                if (feign.isEmpty()) {
                    continue;
                }
                String target = declaredTarget(feign.get());
                edges.add(caller + " -> " + target + " [" + candidate.getName() + "]");
            }
        }
        edges.sort(String::compareTo);
        return edges;
    }

    /** 从注解属性解析目标服务：优先 name，回退 value；去掉 -service 后缀并校验属于九服务清单。 */
    private static String declaredTarget(JavaAnnotation<JavaClass> feign) {
        String raw = stringProperty(feign, "name")
                .orElseGet(() -> stringProperty(feign, "value")
                        .orElseThrow(() -> new AssertionError(
                                "@FeignClient 缺少 name/value 目标属性：" + feign.getOwner().getName())));
        String target = raw.endsWith("-service") ? raw.substring(0, raw.length() - "-service".length()) : raw;
        assertThat(List.of(SERVICES))
                .as("Feign 目标 %s（来自 %s）不在九服务清单内——新目标 = 新边，必须先讨论并更新 rpc-edges.txt",
                        target, feign.getOwner().getName())
                .contains(target);
        return target;
    }

    /** 注解属性可能是 String 或 String[]（FeignClient 的 name/value 都允许数组别名形式）。 */
    private static Optional<String> stringProperty(JavaAnnotation<JavaClass> annotation, String property) {
        return annotation.tryGetExplicitlyDeclaredProperty(property).map(value -> {
            if (value instanceof String s) {
                return s;
            }
            if (value instanceof String[] array && array.length > 0) {
                return array[0];
            }
            throw new AssertionError("注解属性 " + property + " 类型超出预期：" + value);
        });
    }

    private static List<String> readBaseline() {
        try (InputStream in = RpcEdgeAllowListTest.class.getResourceAsStream(BASELINE_RESOURCE)) {
            assertThat(in)
                    .as("基线资源 %s 不存在——允许清单门禁没有比较对象，等于没有门禁", BASELINE_RESOURCE)
                    .isNotNull();
            List<String> lines = new ArrayList<>();
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue; // 注释与空行不参与比对
                    }
                    lines.add(trimmed);
                }
            }
            lines.sort(String::compareTo);
            return lines;
        } catch (IOException e) {
            throw new AssertionError("读取基线失败：" + BASELINE_RESOURCE, e);
        }
    }
}
