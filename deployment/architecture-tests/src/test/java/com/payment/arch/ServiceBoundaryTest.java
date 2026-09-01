package com.payment.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 服务边界测试（Phase 10 / ADR-0029）：把「服务可以被独立拆出去」的结构性前提固化为构建期门禁。
 *
 * <p>Roadmap Phase 10 的验收标准之一是「服务边界测试和运行手册齐全」。本测试就是那条边界测试：
 * 它不测业务逻辑，只强制四条一旦被破坏、服务就不再可能独立演进的不变量。任何一条被违反，
 * 构建立即失败——比等到真要拆分时才发现「原来早就耦合死了」要便宜得多。</p>
 *
 * <ol>
 *   <li><b>服务之间零编译期耦合</b>：跨服务只能走 HTTP/Feign + {@code common-dto}，不能 import 对方的类。</li>
 *   <li><b>领域层不依赖基础设施</b>：{@code domain..} 不得碰 Spring 与 {@code infra..}（否则拆分时领域会被持久化实现绑架）。</li>
 *   <li><b>接入层不直达持久化</b>：{@code api..} / {@code web..} 不得依赖 {@code infra.persistence..}。</li>
 *   <li><b>不预先引入分布式基础设施</b>：不得出现 MQ、JTA/XA 等依赖（ADR-0031：MQ 只在有证据时才评估）。</li>
 * </ol>
 *
 * <p><b>导入方式说明</b>：各服务经 {@code spring-boot-maven-plugin} 重打包，类位于 {@code BOOT-INF/classes}，
 * 无法作为普通依赖被 import；因此这里按目录导入各服务的 {@code target/classes}（见 {@link #serviceClasses()}）。</p>
 */
class ServiceBoundaryTest {

    private static final String[] SERVICES = {
            "merchant", "catalog", "order", "payment", "refund",
            "fulfillment", "entitlement", "reconciliation", "settlement", "ledger"
    };

    private static JavaClasses serviceClasses;

    @BeforeAll
    static void importAllServices() {
        List<Path> roots = new ArrayList<>();
        for (String service : SERVICES) {
            Path classes = moduleClassesDir(service);
            if (classes.toFile().isDirectory()) {
                roots.add(classes);
            }
        }
        serviceClasses = new ClassFileImporter().importPaths(roots);
    }

    /**
     * 防空转门禁：所有结构规则都是「noClasses ... should ...」形式的否定式断言，
     * 一旦目录导入失败（路径变了 / 模块没先编译）就会导入 0 个类，规则全体<em>空转通过</em>，
     * 形成「边界测试全绿但什么都没检查」的假绿。本测试先证明每个服务都被真正导入了。
     */

    @Test
    void everyServiceMustActuallyBeImported() {
        for (String service : SERVICES) {
            long count = serviceClasses.stream()
                    .filter(c -> c.getPackageName().startsWith("com.payment." + service + "."))
                    .count();
            assertThat(count)
                    .as("服务 %s 导入的类数量（为 0 说明 target/classes 未找到，结构规则会空转）", service)
                    .isGreaterThan(5L);
        }
    }

    @Test
    void servicesMustNotDependOnEachOtherAtCompileTime() {
        for (String service : SERVICES) {
            List<String> others = new ArrayList<>();
            for (String candidate : SERVICES) {
                if (!candidate.equals(service)) {
                    others.add("com.payment." + candidate + "..");
                }
            }
            ArchRule rule = noClasses()
                    .that().resideInAPackage("com.payment." + service + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(others.toArray(new String[0]))
                    .because("跨服务只能经 HTTP/Feign + common-dto 契约通信；编译期 import 会锁死服务边界，"
                            + "使服务无法独立部署与演进（ADR-0029 / Constitution §II）");
            rule.check(serviceClasses);
        }
    }

    @Test
    void domainLayerMustNotDependOnInfrastructureOrFramework() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.payment.*.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "org.mybatis..", "com.baomidou..")
                .because("领域层必须框架无关：一旦 domain 依赖 Spring/MyBatis，拆分或复用该领域时"
                        + "就不得不把整个基础设施一起搬走（ADR-0029）");
        rule.check(serviceClasses);
    }

    @Test
    void domainLayerMustNotDependOnItsOwnInfrastructure() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.payment.*.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("com.payment.*.infra..")
                .because("依赖方向必须是 infra → domain（实现依赖抽象），反向依赖会让领域被持久化实现绑架");
        rule.check(serviceClasses);
    }

    @Test
    void inboundAdaptersMustNotReachPersistenceDirectly() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage("com.payment.*.api..", "com.payment.*.web..")
                .should().dependOnClassesThat().resideInAnyPackage("com.payment.*.infra.persistence..")
                .because("接入层（Controller/Filter/Interceptor）必须经应用服务进入领域，"
                        + "直连仓储会绕过事务边界与状态机唯一入口（ADR-0029）");
        rule.check(serviceClasses);
    }

    @Test
    void distributedInfrastructureMustNotBeIntroducedWithoutEvidence() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.apache.kafka..", "org.springframework.amqp..", "com.rabbitmq..",
                        "org.apache.rocketmq..", "jakarta.transaction..", "javax.transaction..",
                        "com.atomikos..", "org.springframework.jms..")
                .because("Phase 10 明确禁止「看起来像微服务」就引入 MQ / JTA-XA；"
                        + "当前一致性由幂等 + Saga + 对账收敛保证，引入异步基础设施必须有真实瓶颈证据（ADR-0031）");
        rule.check(serviceClasses);
    }

    /**
     * 定位某服务的编译输出目录（ledger-service 的 artifactId 与目录名一致，无需特例）。
     *
     * <p>本模块位于 {@code deployment/architecture-tests}，工作目录即该目录，
     * 因此需上溯两级才到仓库根，再进入各服务模块。移动本模块目录时 MUST 同步调整此处层级。</p>
     */
    private static Path moduleClassesDir(String service) {
        return Paths.get("..", "..", service + "-service", "target", "classes");
    }
}
