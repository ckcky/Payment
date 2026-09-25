package com.payment.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
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
    // Feature 015 / P3：refund 已并入 payment-service（com.payment.payment 包，进程内调用替代 Feign），服务数 10→9。
    // spec 038：渠道网关域独立为 com.payment.channelgateway（同属 payment-service 进程，见
    // channelGatewayMustNotDependOnOtherServicesAtCompileTime 的覆盖说明）。

    /**
     * spec 038 / FR-009 ① 的<b>显式白名单</b>——已登记的既有反向依赖（技术债，不静默放宽）。
     *
     * <p>这些类在 038 之前就依赖 {@code com.payment.payment} 的应用/接入层，是「渠道回调分层」
     * 尚未收口的证据。它们的收口属于 <b>037</b>（门面 / {@code PaymentNotifyPort} / 回调分层），
     * 见 spec 038 §4 非目标与 acceptance TD-4 / TD-5。</p>
     *
     * <p><b>037 / T5 已收口 1 个</b>：{@code ChannelPluginCallbackController} 改造后只剩
     * 「收报文、交网关」（依赖全部落在渠道网关域内），已从白名单移除。</p>
     *
     * <p><b>037 / T6 已收口第 2 个</b>：{@code AlipayNotifyController} 按 FR-015 删除，
     * 回调统一走通用端点——它的解析职责下沉到 {@code AlipayChannelAdapter.parseCallback}，
     * 业务校验归 {@code DefaultPaymentNotifyPort}，模态包裹归 {@code ChannelCallbackHandler}，
     * 三处都不在「渠道域反向依赖 payment 应用层」这条线上。剩余 2 个：</p>
     * <ul>
     *   <li>{@code ChannelCallbackController} —— {@code /internal/payments/{paymentNo}/channel-callback}，
     *       是<b>平台内部</b>的 mock 回调入口（非渠道协议），返回 Payment 的 API DTO
     *       （{@code PaymentResponse}）；其归属需要一次独立裁决（移回 {@code payment.api}
     *       还是改契约），不属本 Spec 的机械收口范围；</li>
     *   <li>{@code ChannelCallbackSignatureFilter} —— 复用 {@code payment.web} 的请求体包装器
     *       {@code CachedBodyHttpServletRequest}；要解除依赖需先决定该包装器的归属，
     *       同样是一次独立裁决。</li>
     * </ul>
     *
     * <p>白名单按<b>全限定类名</b>逐条列出而非整包放行：新增任何反向依赖都会立即变红。</p>
     */
    private static final String LEGACY_GATEWAY_TO_PAYMENT_DEPENDENCIES =
            "com\\.payment\\.channelgateway\\.api\\.ChannelCallbackController"
                    + "|com\\.payment\\.channelgateway\\.web\\.ChannelCallbackSignatureFilter";

    /**
     * spec 037 / FR-016 ② 的<b>唯一例外</b>：{@code PaymentNotifyPort} 是 Payment 定义并实现的
     * <b>入向端口</b>，渠道网关域<b>必须</b>依赖它（否则「渠道 → Payment 只经该端口」无从成立）。
     * {@code PayNotifyOutcome} 是它的返回类型，同属例外。
     *
     * <p><b>为什么按「全限定名前缀」而不是简单类名匹配</b>：{@code PayNotifyOutcome.Status}
     * 是嵌套枚举，其简单类名是 {@code Status}——按简单类名写就得额外枚举每个嵌套类型，
     * 漏一个就出现「外层豁免了、嵌套没豁免」的假红（本轮实测就踩到：{@code switch} 上
     * 嵌套枚举会生成 8 条 {@code ordinal()/values()/字段访问} 依赖）。前缀匹配一次覆盖
     * 外层与全部嵌套类型，且不会顺带放过任何其它类。</p>
     *
     * <p>例外<b>只覆盖这两个类型的子树</b>（不是整包放行）：渠道网关域若依赖 payment 的
     * 其它任何应用/接入层类型，规则立即变红。</p>
     */
    private static final DescribedPredicate<JavaClass> INBOUND_PORT_EXCEPTION =
            new DescribedPredicate<>("Payment 定义的入向端口（PaymentNotifyPort / PayNotifyOutcome 及其嵌套类型）") {
                @Override
                public boolean test(JavaClass input) {
                    String name = input.getName();
                    return name.equals("com.payment.payment.application.PaymentNotifyPort")
                            || name.startsWith("com.payment.payment.application.PayNotifyOutcome");
                }
            };

    /** payment 的<b>应用/接入/Web 层实现</b>（入向端口除外）——渠道网关域不得编译期依赖。 */
    private static final DescribedPredicate<JavaClass> FORBIDDEN_PAYMENT_LAYERS =
            resideInAnyPackage("com.payment.payment.application..",
                            "com.payment.payment.api..",
                            "com.payment.payment.web..")
                    .and(not(INBOUND_PORT_EXCEPTION));

    /** spec 037 / FR-016 ① 的禁用目标：渠道网关的<b>内部件</b>（Payment 侧不得触达）。 */
    private static final DescribedPredicate<JavaClass> CHANNEL_GATEWAY_INTERNALS =
            simpleName("ChannelRegistry").or(simpleName("ChannelRouter")).or(simpleName("ChannelPlugin"));

    /** spec 037 / FR-013 / FR-016 ③ 的禁用目标：染色上下文（模态判定的事实源）。 */
    private static final DescribedPredicate<JavaClass> DYE_CONTEXT =
            resideInAnyPackage("com.payment.common.core.dye").and(simpleName("DyeContext"));

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
     * INV-4（Feature 028 / ADR-0073，2026-09-20 FIX-1 修正覆盖范围）：渠道路由的<b>决策</b>与
     * <b>实现</b>必须分居两层，<b>整个应用层</b>不得反向依赖基础设施层的渠道适配器与路由器。
     *
     * <p>允许的依赖方向是 {@code infra.channel → application.channel}（实现依赖抽象）。
     * 若应用层反向 import {@code infra.channel..}，后果不是「代码难看」，而是<b>路由决策被绑死在
     * 具体渠道实现上</b>——此后新增渠道、替换实现、写纯单测都必须拖上整个 infra 包，
     * ADR-0072 的两层结构当场失效。</p>
     *
     * <p><b>覆盖面修正（为什么要放宽到 {@code application..}）</b>：本规则的 {@code that()} 此前只写
     * {@code com.payment.channelgateway.application..}，<b>不覆盖 {@code application..} 主体</b>。
     * 一个子包之差，让三处真实反向依赖（{@code PaymentRetryService} / {@code ChannelQueryService} /
     * {@code PaymentRefundService} 的兼容构造引用 {@code infra.channel.SingleChannelRegistry}）
     * <b>长期逃过门禁</b>——INV-4 名义合规、实际穿透。</p>
     *
     * <p>修法有两半，缺一不可：① 把那条垫片实现（{@code SingleChannelRegistry}，零 infra 依赖）
     * 从 {@code infra.channel} 归位到 {@code application.channel}；② 本规则的 {@code that()}
     * 放宽到 {@code com.payment.payment.application..}，连 {@code .reliability} 一并纳入。</p>
     *
     * <p><b>防空转阳性对照</b>：否定式规则在「被检主体为空」或「禁用目标不存在」时都会静默通过
     * （与「真的检查过」无法区分）。故先断言两侧都真有类——{@code application..} 有主体、
     * {@code infra.channel..} 有实现；任一为空即说明包名或编译产物出了问题，规则已在空转。</p>
     */
    @Test
    void channelRoutingAbstractionMustNotDependOnChannelInfrastructure() {
        // 阳性对照（防空转）：被检主体与禁用目标都必须真实存在
        long applicationOwners = serviceClasses.stream()
                .filter(c -> c.getPackageName().startsWith("com.payment.payment.application"))
                .count();
        long infraChannelClasses = serviceClasses.stream()
                .filter(c -> c.getPackageName().startsWith("com.payment.channelgateway.infra"))
                .count();
        assertThat(applicationOwners)
                .as("com.payment.payment.application.. 必须有类，否则本规则空转（INV-4 防空转对照）")
                .isGreaterThan(5L);
        assertThat(infraChannelClasses)
                .as("com.payment.channelgateway.infra.. 必须有类，否则被禁目标不存在、规则恒通过（INV-4 防空转对照）")
                .isGreaterThan(0L);

        ArchRule rule = noClasses()
                .that().resideInAPackage("com.payment.payment.application..")
                .should().dependOnClassesThat().resideInAPackage("com.payment.channelgateway.infra..")
                .because("依赖方向必须是 infra.channel → application.channel；应用层（含 application 主体，"
                        + "不只是 application.channel 一个子包）反向依赖适配器会把路由决策绑死在具体实现上"
                        + "（INV-4 / ADR-0072、ADR-0073）");
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
                        "com.payment.channelgateway.application..")
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
     * INV-7（spec 030 / ADR-0076）：<b>支付宝 SDK 只能被端口实现类引用</b>。
     *
     * <p>SDK 是第三方闭源依赖：它的类名、异常、返回结构随时可能随版本变化，把它散布到
     * 应用层或领域层，等于让 SDK 的升级节奏绑架平台的业务代码。spec 030 的收口方式是
     * 「端口/适配器」——平台侧定义 {@code AlipayGateway}（只用平台自有类型），
     * 唯一实现 {@code AlipaySdkGateway}（位于 {@code infra.channel.alipay}）独占 SDK 引用。
     * 这样将来要换「纯 JDK 实现」或另一个 SDK 版本，改动被限制在一个类里，**零扩散**。</p>
     *
     * <p>规则落在<b>应用层</b>（{@code application..}，含 {@code application.channel..} 的端口定义）
     * 与<b>领域层</b>（{@code domain..}）——这两层都不许出现 {@code com.alipay.api}。</p>
     *
     * <p><b>包名口径（2026-09-20 修正）</b>：SDK 的 <em>Maven 坐标</em>是
     * {@code com.alipay.sdk:alipay-sdk-java}，但它编译出来的 <em>Java 包</em>是
     * {@code com.alipay.api}（{@code AlipayClient} / {@code AlipaySignature} …）。
     * 本规则此前写作 {@code com.alipay.sdk..}——<b>该包不存在，规则恒通过</b>，
     * 是一条空转的假绿门禁：即使有人在应用层 import SDK，构建也不会红。
     * 现按真实包名匹配，并加<b>阳性对照</b>把这类笔误钉死（见下）。</p>
     *
     * <p><b>为什么用字符串包名而非 {@code dependOnClassesThat().resideInAPackage(…)} 的常量</b>：
     * SDK 不在本模块的 classpath 上（本模块只按目录导入各服务字节码），故只能用包名字符串匹配。
     * 正因如此，包名写错不会有编译错误——只会静默失效。</p>
     */
    @Test
    void alipaySdkMustBeConfinedToItsInfrastructureAdapter() {
        // 阳性对照（防空转）：先证明「收口主体存在」且「它确实依赖 com.alipay.api..」。
        // 否定式规则（noClasses…）在没有命中任何类时与「真的检查过」无法区分，
        // 故必须由正向的证据先证明这个包名在 ArchUnit 依赖模型里是「可命中的」。
        JavaClass sdkAdapter = serviceClasses.stream()
                .filter(c -> c.getSimpleName().equals("AlipaySdkGateway"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "找不到 AlipaySdkGateway：INV-7 的收口主体不存在，本规则将空转（假绿）"));
        boolean referencesSdkPackage = sdkAdapter.getDirectDependenciesFromSelf().stream()
                .anyMatch(d -> d.getTargetClass().getPackageName().startsWith("com.alipay.api"));
        assertThat(referencesSdkPackage)
                .as("AlipaySdkGateway 必须真实引用 com.alipay.api..；为 false 说明包名又写错了，"
                        + "下面的否定式规则会静默空转（INV-7 防空转对照）")
                .isTrue();

        ArchRule rule = noClasses()
                .that().resideInAnyPackage(
                        "com.payment.payment.application..",
                        "com.payment.payment.domain..")
                .should().dependOnClassesThat().resideInAPackage("com.alipay.api..")
                .because("支付宝 SDK 是第三方闭源依赖，必须被端口实现 AlipaySdkGateway（infra.channel.alipay）独占；"
                        + "应用层/领域层一旦 import 它，SDK 的升级节奏就会绑架业务代码，换实现将全仓扩散（INV-7 / ADR-0076）");
        rule.check(serviceClasses);
    }

    /**
     * INV-7 的<b>第二家渠道实例</b>（渠道插件化 / STRIPE-01）：Stripe SDK 只能被端口实现类引用。
     *
     * <p>支付宝接入时定下了「SDK 收口」这条纪律（见
     * {@link #alipaySdkMustBeConfinedToItsInfrastructureAdapter()}），但它此前<b>只为支付宝写过一次</b>——
     * 这类「按渠道手写一条规则」的门禁，接第二家渠道时最容易漏。
     * 漏掉的后果是真实的：SDK 类一旦散进 {@code application/**}，
     * Stripe 的版本升级就会绑架平台业务代码，而「换纯 JDK 实现」将变成全仓扩散。</p>
     *
     * <p>本规则与支付宝那条<b>逐字同构</b>（含阳性对照）：目标是
     * {@code com.stripe..}，收口主体是 {@code StripeSdkGateway}。
     * 将来第三家渠道接入，应照此模板再加一条——更好的做法是抽出参数化规则，
     * 但那属于「第三次重复时才做」的重构，现在保持与既有规则一致更好读。</p>
     */
    @Test
    void stripeSdkMustBeConfinedToItsInfrastructureAdapter() {
        JavaClass sdkAdapter = serviceClasses.stream()
                .filter(c -> c.getSimpleName().equals("StripeSdkGateway"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "找不到 StripeSdkGateway：INV-7 的收口主体不存在，本规则将空转（假绿）"));
        boolean referencesSdkPackage = sdkAdapter.getDirectDependenciesFromSelf().stream()
                .anyMatch(d -> d.getTargetClass().getPackageName().startsWith("com.stripe."));
        assertThat(referencesSdkPackage)
                .as("StripeSdkGateway 必须真实引用 com.stripe..；为 false 说明包名写错，"
                        + "下面的否定式规则会静默空转（INV-7 防空转对照）")
                .isTrue();

        ArchRule rule = noClasses()
                .that().resideInAnyPackage(
                        "com.payment.payment.application..",
                        "com.payment.payment.domain..",
                        "com.payment.payment.api..")
                .should().dependOnClassesThat().resideInAPackage("com.stripe..")
                .because("Stripe SDK 是第三方依赖，必须被端口实现 StripeSdkGateway"
                        + "（infra.channel.stripe）独占；应用层/领域层/接入层一旦 import 它，"
                        + "SDK 的升级节奏就会绑架业务代码，换实现将全仓扩散（INV-7 / ADR-0076）");
        rule.check(serviceClasses);
    }

    /**
     * 渠道插件化内核门禁（SPI-10）：<b>内核不认识任何具体渠道</b>。
     *
     * <p>微内核 + 插件化的成立条件是「接新渠道不改内核」。若 {@code application/**}
     * 里出现了 {@code Alipay} / {@code Stripe} / {@code Wechat} / {@code Douyin} 这些
     * 渠道专属类名，就说明渠道概念泄漏进了内核——此后每接一家渠道都要改内核，
     * 插件化名存实亡。</p>
     *
     * <p><b>为什么按类名匹配而不是按包</b>：渠道实现都在 {@code infra.channel.<vendor>}，
     * 用包规则的话，本类（架构测试）自己提到这些字符串不会误判，但内核里若只是
     * 在 Javadoc 中提及也无所谓——ArchUnit 只扫<b>编译期依赖</b>，注释不算。
     * 故按「类名前缀」匹配足够，且能同时覆盖「未来有人把渠道类放到别的包」的情况。</p>
     *
     * <p><b>豁免</b>：{@code application.channel.spi..} 是插件契约本身，
     * 它的 Javadoc 会举例说明各家渠道的协议差异——注释不产生编译期依赖，无需豁免。</p>
     */
    @Test
    void channelKernelMustNotKnowAnyConcreteChannel() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.payment.payment.application..")
                .should().dependOnClassesThat().haveSimpleNameStartingWith("Alipay")
                .orShould().dependOnClassesThat().haveSimpleNameStartingWith("Stripe")
                .orShould().dependOnClassesThat().haveSimpleNameStartingWith("Wechat")
                .orShould().dependOnClassesThat().haveSimpleNameStartingWith("Douyin")
                .because("微内核 + 插件化的前提是「接新渠道不改内核」；application/** 一旦依赖具体渠道类，"
                        + "渠道差异就泄漏进内核，插件化名存实亡（渠道插件化内核 / SPI-10）");
        rule.check(serviceClasses);
    }

    /**
     * INV-3（spec 030 / FR-120、FR-122）：<b>染色只决定协议实现，不参与路由决策</b>。
     *
     * <p>染色（{@code X-Dye-Tag} ⇒ {@code DyeContext}）回答的是「这次调用走 mock 还是真实沙箱」，
     * 是一个<b>实现选择</b>；而路由（{@code ChannelRouter}）回答的是「这笔订单该走哪家渠道」，
     * 是一个<b>业务决策</b>。两者一旦混在一起，「同一订单换个环境就路由到不同渠道」——
     * 那是把钱送错地方的隐患，且会让演示环境的结论无法外推到生产。</p>
     *
     * <p>因此本规则禁止 {@code ChannelRouter} 读 {@code DyeContext}：路由必须是染色的<b>纯函数</b>，
     * 相同 {@code RouteContext} 在两种染色下选出同一 {@code channelCode}（由
     * {@code DyeNotAffectingRoutingTest} 从行为侧验证）。</p>
     */
    @Test
    void channelRouterMustNotReadDyeContext() {
        ArchRule rule = noClasses()
                .that().haveSimpleName("ChannelRouter")
                .should().dependOnClassesThat().resideInAPackage("com.payment.common.core.dye..")
                .because("染色只决定协议实现（mock/沙箱），路由是业务决策；二者混淆会导致同一订单在不同环境"
                        + "路由到不同渠道，使演示结论无法外推（INV-3 / FR-120、FR-122）");
        rule.check(serviceClasses);
    }

    /**
     * <b>spec 038 覆盖修补</b>：{@code com.payment.channelgateway..} 不得在编译期依赖任何<b>其它服务</b>。
     *
     * <p><b>为什么必须单独补这一条</b>：{@link #servicesMustNotDependOnEachOtherAtCompileTime()}
     * 的主体是 {@code com.payment.<service>..}。038 把 40 个渠道件从 {@code com.payment.payment.application.channel..}
     * / {@code com.payment.payment.infra.channel..} 搬到新顶层包 {@code com.payment.channelgateway..} 之后，
     * 这些类<b>不再落在任何 {@code SERVICES} 前缀下</b>——原有的跨服务门禁对它们<b>静默失效</b>，
     * 形成「门禁看似还在、实际漏检一整块」的假绿。本方法把覆盖面补回来。</p>
     *
     * <p>{@code payment} 从禁用清单中排除：{@code channelgateway} 与 {@code payment} 同属
     * payment-service 进程，二者之间的方向性由
     * {@link #paymentAndChannelGatewayMustKeepOneWayDependency()} 单独约束。</p>
     */
    @Test
    void channelGatewayMustNotDependOnOtherServicesAtCompileTime() {
        long gatewayClasses = serviceClasses.stream()
                .filter(c -> c.getPackageName().startsWith("com.payment.channelgateway."))
                .count();
        assertThat(gatewayClasses)
                .as("com.payment.channelgateway.. 必须有类，否则本规则空转（038 覆盖修补的防空转对照）")
                .isGreaterThan(20L);

        List<String> others = new ArrayList<>();
        for (String candidate : SERVICES) {
            if (!candidate.equals("payment")) {
                others.add("com.payment." + candidate + "..");
            }
        }
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.payment.channelgateway..")
                .should().dependOnClassesThat().resideInAnyPackage(others.toArray(new String[0]))
                .because("渠道网关件搬出 com.payment.payment.. 后，若不单独覆盖就会脱离跨服务门禁；"
                        + "跨服务只能经 HTTP/Feign + common-dto 通信（ADR-0029）");
        rule.check(serviceClasses);
    }

    /**
     * FR-009 ①（spec 038）：{@code payment} 与 {@code channelgateway} 之间保持<b>单向依赖</b>。
     *
     * <p>允许的方向是 {@code payment → channelgateway}（资金动作域调用渠道网关域）。
     * 反向依赖只允许落在<b>领域值类型</b>（{@code payment.domain} 的 {@code PaymentAttempt} /
     * {@code PaymentAttemptErrorType}）——那是既有的 DIP：渠道层定义端口
     * （{@code ChannelAttemptRecorder} / {@code ChannelResult}），payment 侧实现它。</p>
     *
     * <p>被禁止的是反向依赖 payment 的<b>应用层 / 接入层 / Web 层</b>：渠道网关域一旦直连
     * {@code PaymentCallbackService} / {@code PaymentApplicationService}，就不再是「可独立演进的
     * 进程内微服务边界」，而是资金域的一个内嵌实现——038 建立的包边界当场失效。</p>
     *
     * <p><b>既有违规不静默放宽</b>：038 之前就存在的反向依赖按 spec 038 T6 的要求<b>登记为技术债</b>
     * 并在此逐条白名单化（见 {@link #LEGACY_GATEWAY_TO_PAYMENT_DEPENDENCIES}），收口归 037。
     * 037 / T5 已收口其中 1 个（{@code ChannelPluginCallbackController}），
     * T6 又收口 1 个（{@code AlipayNotifyController} 按 FR-015 删除）。</p>
     *
     * <p><b>唯一例外是入向端口</b>：{@code PaymentNotifyPort}（及其返回类型 {@code PayNotifyOutcome}）
     * 由 Payment 定义并实现，渠道网关域<b>必须</b>依赖它才能把回调结果交给 Payment
     * （FR-010 / FR-016 ② 的括号例外）。例外按简单类名逐个列出，不是整包放行。</p>
     *
     * <p><b>防空转阳性对照</b>：先证明两侧都真有类，否则否定式规则恒通过。</p>
     */
    @Test
    void paymentAndChannelGatewayMustKeepOneWayDependency() {
        long gatewayClasses = serviceClasses.stream()
                .filter(c -> c.getPackageName().startsWith("com.payment.channelgateway."))
                .count();
        long paymentClasses = serviceClasses.stream()
                .filter(c -> c.getPackageName().startsWith("com.payment.payment."))
                .count();
        assertThat(gatewayClasses)
                .as("com.payment.channelgateway.. 必须有类，否则本规则空转（FR-009 ① 防空转对照）")
                .isGreaterThan(20L);
        assertThat(paymentClasses)
                .as("com.payment.payment.. 必须有类，否则本规则空转（FR-009 ① 防空转对照）")
                .isGreaterThan(20L);

        ArchRule rule = noClasses()
                .that().resideInAPackage("com.payment.channelgateway..")
                .and().haveNameNotMatching(LEGACY_GATEWAY_TO_PAYMENT_DEPENDENCIES)
                .should().dependOnClassesThat(FORBIDDEN_PAYMENT_LAYERS)
                .because("依赖方向必须是 payment → channelgateway；反向依赖 payment 的应用/接入层会把"
                        + "渠道网关域绑死在资金域实现上，进程内微服务边界失效"
                        + "（FR-009 ① / FR-016 ② / INV-1、INV-2；唯一例外是 Payment 定义的入向端口 "
                        + "PaymentNotifyPort / PayNotifyOutcome；白名单见 LEGACY_GATEWAY_TO_PAYMENT_DEPENDENCIES）");
        rule.check(serviceClasses);
    }

    /**
     * FR-016 ①（spec 037 / T7）：<b>Payment 的应用层与接入层不得触达渠道网关的内部件</b>。
     *
     * <p>038 的 {@link #channelRoutingAbstractionMustNotDependOnChannelInfrastructure()} 只约束了
     * <b>反方向</b>（{@code payment.application} 不得依赖 {@code channelgateway.infra}）——
     * 于是「Payment 侧直接持有 {@code ChannelRegistry} / {@code ChannelRouter} / {@code ChannelPlugin}」
     * 这条<b>正方向的越界</b>长期没有门禁兜底：只要它们恰好落在
     * {@code channelgateway.application}（而非 {@code .infra}），规则就看不见。</p>
     *
     * <p>而这正是 spec 037 §1.1 记录的现状：改造前 Payment 侧有 9 个类直接依赖这三件套。
     * T4 用门面把它们全部收口，本规则把「收口」变成<b>不可回退</b>的构建期事实——
     * 否则下一次「顺手 resolve 一下」就会悄悄把内部结构重新泄出去。</p>
     *
     * <p><b>为什么只禁这三件套而不是整个 {@code channelgateway.application}</b>：Payment
     * <b>必须</b>依赖 {@code ChannelGateway}（门面）与 {@code ChannelResult}（结果值类型），
     * 它们就在同一个包里。禁整包等于禁掉合法的边界调用。被禁的是「内部结构」：
     * 注册表（怎么找实现）、路由器（怎么选渠道）、插件契约（渠道长什么样）。</p>
     *
     * <p><b>防空转阳性对照</b>：先证明①被检主体真有类；②被禁目标在导入的字节码里真实存在
     * （否则 {@code noClasses…should().dependOnClassesThat(…)} 恒通过）；③Payment 侧确实
     * 「能够」依赖它们（同进程同 classpath，不存在「想依赖也依赖不到」的伪安全）。</p>
     */
    @Test
    void paymentApplicationAndApiMustNotReachChannelGatewayInternals() {
        long paymentAppAndApiClasses = serviceClasses.stream()
                .filter(c -> c.getPackageName().startsWith("com.payment.payment.application")
                        || c.getPackageName().startsWith("com.payment.payment.api"))
                .count();
        assertThat(paymentAppAndApiClasses)
                .as("com.payment.payment.application.. / .api.. 必须有类，否则本规则空转"
                        + "（FR-016 ① 防空转对照）")
                .isGreaterThan(20L);

        long internalsPresent = serviceClasses.stream()
                .filter(CHANNEL_GATEWAY_INTERNALS)
                .count();
        assertThat(internalsPresent)
                .as("渠道网关内部件（ChannelRegistry / ChannelRouter / ChannelPlugin）必须真实存在，"
                        + "否则被禁目标不存在、规则恒通过（FR-016 ① 防空转对照）")
                .isEqualTo(3L);

        // ③ 同一 classpath 内可命中：网关域自己就在用这三件套（证明依赖是「够得着」的）
        long gatewayOwnUsers = serviceClasses.stream()
                .filter(c -> c.getPackageName().startsWith("com.payment.channelgateway."))
                .filter(c -> c.getDirectDependenciesFromSelf().stream()
                        .anyMatch(d -> CHANNEL_GATEWAY_INTERNALS.test(d.getTargetClass())))
                .count();
        assertThat(gatewayOwnUsers)
                .as("渠道网关域必须有类真的在用这三件套，证明它们在本 classpath 内可被依赖"
                        + "（FR-016 ① 防空转对照：若不可命中，规则是伪安全）")
                .isGreaterThan(0L);

        ArchRule rule = noClasses()
                .that().resideInAnyPackage("com.payment.payment.application..",
                        "com.payment.payment.api..")
                .should().dependOnClassesThat(CHANNEL_GATEWAY_INTERNALS)
                .because("Payment 侧 MUST 只经 ChannelGateway 门面调用渠道网关（FR-007 / INV-1 / SC-002）；"
                        + "直接依赖 ChannelRegistry / ChannelRouter / ChannelPlugin 会让渠道网关的内部结构"
                        + "对 Payment 透明——改内核要动 Payment，进程内微服务边界失效（FR-016 ①）");
        rule.check(serviceClasses);
    }

    /**
     * FR-013 / FR-016 ③（spec 037 / T7）：<b>模态判定内聚在渠道网关域</b>——
     * Payment 的应用层与接入层不得读取染色上下文 {@code DyeContext}。
     *
     * <p>染色回答的是「这次调用走 mock 还是真实沙箱」，是<b>渠道协议实现的选择</b>；
     * 它一旦散进 Payment 的编排层，就出现两个域各自解释「模态是什么」的局面——
     * 改造前正是如此：模态包裹散落在退款、主动查询、超时扫描、建单入口四处。</p>
     *
     * <p>037 / T5b 的收口方式是把「模态的<b>施加</b>」收进 {@code ChannelGateway}
     * （带 {@code DyeMode} 的 {@code refund} / {@code query} 重载 + {@code isSandboxRequest()} 探针），
     * Payment 侧只回答「这一笔当初记的是哪种模态」。本规则把该收口钉成构建期事实。</p>
     *
     * <p><b>为什么只禁 {@code DyeContext} 而不是整个 {@code com.payment.common.core.dye} 包</b>：
     * {@code DyeMode} 是<b>值类型</b>，{@code payment.domain.PaymentAttempt#getChannelMode()}
     * 就返回它（落库模态的只读派生访问器），禁整包会连领域模型一起禁掉。
     * 要禁的是「谁去读 ThreadLocal 上的当前染色」这个<b>判定动作</b>。</p>
     *
     * <p><b>覆盖面说明（与 FR-016 ③ 字面的差异，已上报）</b>：本规则的 {@code that()} 覆盖
     * {@code payment.application..} 与 {@code payment.api..}——这是 FR-013 的原文口径
     * （「{@code DyeContext} 不得在 Payment <b>应用/api 层</b>被读取」）。FR-016 ③ 的措辞
     * 更宽（「仅限渠道网关域」），但那会要求把 {@code payment.infra} 的两个写入口实现
     * （{@code ChannelAttemptRecorderImpl}、{@code InMemoryPaymentAttemptRepository}）
     * 也搬出 payment 域——那是一次<b>独立的服务边界裁决</b>，不属本 Spec 的机械收口范围，
     * 故本轮按 FR-013 口径落地并登记为后续项。</p>
     *
     * <p><b>防空转阳性对照</b>：先证明①被检主体真有类；②渠道网关域确实有类在读它
     * （证明该依赖在导入的字节码模型里「可命中」）。</p>
     *
     * <p><b>为什么不断言 {@code DyeContext} 本身「存在于导入的类集里」</b>：本模块只按目录导入
     * 各<b>服务</b>的 {@code target/classes}，{@code common-core} 不在其中——{@code DyeContext}
     * 在 ArchUnit 的模型里是<b>桩类</b>（stub，带全限定名但无成员）。断言它「存在」必然为假；
     * 而依赖本身（{@code 渠道网关类 → DyeContext}）照样被建模，故用「网关域读者数 &gt; 0」
     * 作为可命中性的证据。</p>
     */
    @Test
    void dyeContextMustStayInsideChannelGatewayDomain() {
        long paymentAppAndApiClasses = serviceClasses.stream()
                .filter(c -> c.getPackageName().startsWith("com.payment.payment.application")
                        || c.getPackageName().startsWith("com.payment.payment.api"))
                .count();
        assertThat(paymentAppAndApiClasses)
                .as("com.payment.payment.application.. / .api.. 必须有类，否则本规则空转"
                        + "（FR-013 防空转对照）")
                .isGreaterThan(20L);

        long gatewayDyeReaders = serviceClasses.stream()
                .filter(c -> c.getPackageName().startsWith("com.payment.channelgateway."))
                .filter(c -> c.getDirectDependenciesFromSelf().stream()
                        .anyMatch(d -> DYE_CONTEXT.test(d.getTargetClass())))
                .count();
        assertThat(gatewayDyeReaders)
                .as("渠道网关域必须有类真的在读 DyeContext（模态判定的施加点），"
                        + "否则被禁目标不可命中、规则是伪安全（FR-013 防空转对照）")
                .isGreaterThan(0L);

        ArchRule rule = noClasses()
                .that().resideInAnyPackage("com.payment.payment.application..",
                        "com.payment.payment.api..")
                .should().dependOnClassesThat(DYE_CONTEXT)
                .because("模态（MOCK/SANDBOX）判定 MUST 内聚在渠道网关域（FR-013）；"
                        + "Payment 应用/api 层读染色上下文会让「模态是什么」变成两个域各自解释的概念——"
                        + "改造前模态包裹散落在退款/查询/超时扫描/建单入口四处，正是这种失真"
                        + "（FR-016 ③；施加点收在 ChannelGateway 的 DyeMode 重载与 isSandboxRequest 探针）");
        rule.check(serviceClasses);
    }

    /**
     * FR-009 ②（spec 038）：{@code com.payment.refund} 顶层包必须<b>不存在</b>，
     * 且渠道网关域不得引用它。
     *
     * <p>038 的核心结论是「{@code refund} 不是与 {@code payment} 平级的第三个域，而是 payment 域内的
     * 一个<b>操作切片</b>」（INV-2）。若 {@code com.payment.refund} 重新出现，或渠道网关域反向引用它，
     * 说明包边界回退——这是一条应当长期保留的<b>防回退</b>门禁，而非一次性迁移检查。</p>
     *
     * <p><b>防空转阳性对照</b>：先证明退款能力确实存在且<b>确实已迁入</b> payment 域
     * （{@code com.payment.payment.application.refund..} 有类）。否则「找不到 com.payment.refund」
     * 可能只是因为编译产物没导入，规则毫无意义。</p>
     */
    @Test
    void channelGatewayMustNotReferenceLegacyRefundPackage() {
        long migratedRefundClasses = serviceClasses.stream()
                .filter(c -> c.getPackageName().startsWith("com.payment.payment.application.refund"))
                .count();
        assertThat(migratedRefundClasses)
                .as("退款操作切片 com.payment.payment.application.refund.. 必须有类；"
                        + "为 0 说明退款类没被导入或未完成迁移，本规则会空转（FR-009 ② 防空转对照）")
                .isGreaterThan(5L);

        ArchRule legacyPackageMustNotExist = noClasses()
                .should().resideInAPackage("com.payment.refund..")
                .because("refund 是 payment 域内的操作切片，不是独立域；重建 com.payment.refund 顶层包即边界回退"
                        + "（FR-009 ② / INV-2）");
        legacyPackageMustNotExist.check(serviceClasses);

        ArchRule rule = noClasses()
                .that().resideInAPackage("com.payment.channelgateway..")
                .should().dependOnClassesThat().resideInAPackage("com.payment.refund..")
                .because("渠道网关域不得引用已被消灭的退款顶层包（FR-009 ②）");
        rule.check(serviceClasses);
    }

    /**
     * FR-009 ③（spec 038）：<b>渠道插件必须位于 {@code com.payment.channelgateway.infra.<channel>}</b>。
     *
     * <p>插件化的落点约定：新增一家渠道 = 新增一个 {@code infra/<channel>/} 包，内含该渠道的
     * 插件（{@code ChannelPlugin} 实现）、Gateway 端口与 SdkGateway。这条约定一旦松动，
     * 渠道件会重新散落回 {@code infra} 根或内核包，「接新渠道不改内核」的前提就没了。</p>
     *
     * <p><b>规则口径</b>：主体取「{@code ChannelPlugin} 的<b>具体实现</b>」——
     * 用 {@code areNotInterfaces()} 排除插件契约接口本身，用
     * {@code resideOutsideOfPackage(application.spi)} 排除模板基类 {@code AbstractChannelPlugin}
     * （它是内核的一部分，理应留在 {@code application.spi}）。</p>
     *
     * <p><b>防空转阳性对照</b>：先证明「内核之外确实存在具体插件」。当前只有 Stripe 一家，
     * 若将来插件被挪走或删空，本断言先红——比规则静默通过要好。</p>
     */
    @Test
    void channelPluginsMustResideInTheirInfraChannelPackage() {
        long concretePlugins = serviceClasses.stream()
                .filter(c -> !c.isInterface())
                .filter(c -> c.isAssignableTo("com.payment.channelgateway.application.spi.ChannelPlugin"))
                .filter(c -> !c.getPackageName().startsWith("com.payment.channelgateway.application.spi"))
                .count();
        assertThat(concretePlugins)
                .as("内核（application.spi）之外必须存在具体渠道插件实现，否则本规则空转"
                        + "（FR-009 ③ 防空转对照）")
                .isGreaterThan(0L);

        ArchRule rule = classes()
                .that().areAssignableTo("com.payment.channelgateway.application.spi.ChannelPlugin")
                .and().areNotInterfaces()
                .and().resideOutsideOfPackage("com.payment.channelgateway.application.spi")
                .should().resideInAPackage("com.payment.channelgateway.infra..")
                .because("渠道插件必须按渠道分包落在 channelgateway.infra.<channel>，"
                        + "否则渠道件重新散落、插件化落点约定失效（FR-009 ③ / INV-3）");
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
