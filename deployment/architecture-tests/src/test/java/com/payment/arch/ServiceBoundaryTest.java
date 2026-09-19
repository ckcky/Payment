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
 *   <li><b>不预先引入分布式基础设施</b>：不得出现 MQ、JTA/XA 等外部消息中间件依赖
 *       （ADR-0031：MQ 只在有证据时才评估）。<b>禁令清单本身不变</b>——见
 *       {@link #distributedInfrastructureMustNotBeIntroducedWithoutEvidence()} 的通道定位说明。</li>
 * </ol>
 *
 * <p><b>导入方式说明</b>：各服务经 {@code spring-boot-maven-plugin} 重打包，类位于 {@code BOOT-INF/classes}，
 * 无法作为普通依赖被 import；因此这里按目录导入各服务的 {@code target/classes}（见 {@link #serviceClasses()}）。</p>
 */
class ServiceBoundaryTest {

    private static final String[] SERVICES = {
            "merchant", "catalog", "order", "payment",
            "fulfillment", "entitlement", "reconciliation", "settlement", "ledger"
    };
    // Feature 015 / P3：refund 已并入 payment-service（com.payment.refund 包，进程内调用替代 Feign），服务数 10→9。

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

    /**
     * 「不预先引入分布式基础设施」门禁——<b>禁用清单自 Phase 10 起从未放宽</b>。
     *
     * <p><b>通道定位（spec 029 / ADR-0074）</b>：spec 029 引入的跨服务异步通道是
     * <b>Redis Streams</b>，不是消息中间件：它复用已在技术栈内、且本就被用作缓存的 Redis，
     * 没有带来新的<b>运维实体</b>（无新 broker / 新集群 / 新部署单元 / 新运维手册），
     * 因此不构成 ADR-0031 所要防的「为了像微服务而引入 MQ」。</p>
     *
     * <p><b>因此本规则的禁用清单维持原样</b>：Kafka / RabbitMQ / RocketMQ / JMS / JTA-XA
     * 一律继续禁止，Redis 通道也不在禁用清单里——它由 {@code common-redis-mq} 的包结构
     * （{@code com.payment.common.mq}）与 {@link #servicesMustNotDependOnEachOtherAtCompileTime()}
     * 共同约束，无需在此新开规则。</p>
     *
     * <p>若将来有人把通道换成上述任一 MQ 实现，本测试会立即变红——这正是它存在的意义：
     * 换 MQ 是一个<b>需要 ADR 的架构决策</b>，不该由一次依赖升级悄悄完成。</p>
     */
    @Test
    void distributedInfrastructureMustNotBeIntroducedWithoutEvidence() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.apache.kafka..", "org.springframework.amqp..", "com.rabbitmq..",
                        "org.apache.rocketmq..", "jakarta.transaction..", "javax.transaction..",
                        "com.atomikos..", "org.springframework.jms..")
                .because("Phase 10 明确禁止「看起来像微服务」就引入 MQ / JTA-XA；"
                        + "当前一致性由幂等 + Saga + 对账收敛保证，引入异步基础设施必须有真实瓶颈证据（ADR-0031）。"
                        + "spec 029 选择的 Redis Streams 通道不在清单内：它复用既有 Redis，"
                        + "未新增运维实体，故清单保持不变（ADR-0074）");
        rule.check(serviceClasses);
    }

    /**
     * INV-4（Feature 028 / ADR-0073）：渠道路由的<b>决策</b>与<b>实现</b>必须分居两层，
     * 应用层不得反向依赖基础设施层的渠道适配器与路由器。
     *
     * <p>允许的依赖方向是 {@code infra.channel → application.channel}（实现依赖抽象）。
     * 若 {@code application.channel..} 反向 import {@code infra.channel..}，后果不是「代码难看」，
     * 而是<b>路由决策被绑死在具体渠道实现上</b>——此后新增渠道、替换实现、写纯单测都必须
     * 拖上整个 infra 包，ADR-0072 的两层结构当场失效。</p>
     */
    @Test
    void channelRoutingAbstractionMustNotDependOnChannelInfrastructure() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.payment.payment.application.channel..")
                .should().dependOnClassesThat().resideInAPackage("com.payment.payment.infra.channel..")
                .because("依赖方向必须是 infra.channel → application.channel；"
                        + "应用层反向依赖适配器会把路由决策绑死在具体实现上（INV-4 / ADR-0072、ADR-0073）");
        rule.check(serviceClasses);
    }

    /**
     * INV-5（Feature 028 / FR-002）：{@code payment_attempts} 的<b>写入口唯一</b>归属于渠道层端口
     * {@code ChannelAttemptRecorder}；应用层不得直接依赖 {@code PaymentAttemptRepository}。
     *
     * <p>一条支付尝试行的生命周期跨了「应用层编排」与「渠道层调用」两个关注点。若应用层既能
     * 经端口收敛、又能直连仓储改写，同一行就有两个写入口——两个写入口意味着两套不变量，
     * 而它们必然会漂移（典型症状：一处收敛了 status 忘了 channel_reference，对账就缺流水号）。</p>
     *
     * <p><b>本规则只约束「写」</b>：{@code save} 是唯一的写方法，故规则针对
     * {@code PaymentAttemptRepository.save(...)} 的调用点；纯读（{@code findById} /
     * {@code findByPaymentNo}）不受约束——匹配目标行、抽取渠道引用、拼装对账事实都需要读，
     * 把读也禁掉只会逼出「绕道反射或新开只读仓储」的更差做法。</p>
     *
     * <p><b>豁免</b>：
     * <ul>
     *   <li>{@code application.reliability..} —— UNKNOWN 主动查询的只读反向路径；</li>
     *   <li>{@code application.channel..} —— 端口自身所在包。</li>
     * </ul>
     * {@code ChannelAttemptRecorders}（把仓储适配成端口的兼容垫片）是全类豁免：
     * 它的职责就是持有仓储并转发，是端口本身的实现细节。</p>
     *
     * <p><b>为什么用「按类名 + 方法名」的字符串匹配而不是 {@code callMethod(PaymentAttemptRepository.class, …)}</b>：
     * 各服务经 {@code spring-boot-maven-plugin} 重打包，类在 {@code BOOT-INF/classes}，
     * 本模块无法在编译期引用它们的类型（见类注释）。ArchUnit 的字符串重载对这类
     * 「只导入字节码、不建依赖」的用法是唯一可行路径。</p>
     */
    @Test
    void attemptTableWriteEntryMustBeOwnedByChannelPort() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.payment.payment.application..")
                .and().resideOutsideOfPackages(
                        "com.payment.payment.application.reliability..",
                        "com.payment.payment.application.channel..")
                .and().haveNameNotMatching(
                        "com\\.payment\\.payment\\.application\\.ChannelAttemptRecorders.*")
                .should().callMethod(
                        "com.payment.payment.domain.PaymentAttemptRepository", "save",
                        "com.payment.payment.domain.PaymentAttempt")
                .because("payment_attempts 的写入口唯一归属 channel 层端口 ChannelAttemptRecorder；"
                        + "应用层直连仓储写会产生第二个写入口，两套不变量必然漂移（INV-5 / FR-002）");
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
