package com.payment.arch;

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
     * INV-4（Feature 028 / ADR-0073，2026-09-20 FIX-1 修正覆盖范围）：渠道路由的<b>决策</b>与
     * <b>实现</b>必须分居两层，<b>整个应用层</b>不得反向依赖基础设施层的渠道适配器与路由器。
     *
     * <p>允许的依赖方向是 {@code infra.channel → application.channel}（实现依赖抽象）。
     * 若应用层反向 import {@code infra.channel..}，后果不是「代码难看」，而是<b>路由决策被绑死在
     * 具体渠道实现上</b>——此后新增渠道、替换实现、写纯单测都必须拖上整个 infra 包，
     * ADR-0072 的两层结构当场失效。</p>
     *
     * <p><b>覆盖面修正（为什么要放宽到 {@code application..}）</b>：本规则的 {@code that()} 此前只写
     * {@code com.payment.payment.application.channel..}，<b>不覆盖 {@code application..} 主体</b>。
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
                .filter(c -> c.getPackageName().startsWith("com.payment.payment.infra.channel"))
                .count();
        assertThat(applicationOwners)
                .as("com.payment.payment.application.. 必须有类，否则本规则空转（INV-4 防空转对照）")
                .isGreaterThan(5L);
        assertThat(infraChannelClasses)
                .as("com.payment.payment.infra.channel.. 必须有类，否则被禁目标不存在、规则恒通过（INV-4 防空转对照）")
                .isGreaterThan(0L);

        ArchRule rule = noClasses()
                .that().resideInAPackage("com.payment.payment.application..")
                .should().dependOnClassesThat().resideInAPackage("com.payment.payment.infra.channel..")
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
     * 定位某服务的编译输出目录（ledger-service 的 artifactId 与目录名一致，无需特例）。
     *
     * <p>本模块位于 {@code deployment/architecture-tests}，工作目录即该目录，
     * 因此需上溯两级才到仓库根，再进入各服务模块。移动本模块目录时 MUST 同步调整此处层级。</p>
     */
    private static Path moduleClassesDir(String service) {
        return Paths.get("..", "..", service + "-service", "target", "classes");
    }
}
