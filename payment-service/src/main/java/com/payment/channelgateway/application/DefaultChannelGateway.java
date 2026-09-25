package com.payment.channelgateway.application;

import com.payment.common.core.dye.DyeContext;
import com.payment.common.core.dye.DyeMode;
import com.payment.common.dto.channel.PayCredential;
import com.payment.common.dto.channel.PaymentScene;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.function.Supplier;

/**
 * 渠道网关门面实现（spec 037 / FR-007 / FR-008）。
 *
 * <p>薄委托：把「解析渠道实现」这件事收进网关域，对外只暴露业务语义的方法。
 * 除解析与委托外<b>不加任何逻辑</b>——门面一旦开始做业务判断，它就从边界退化成了编排层。</p>
 *
 * <h3>为什么落在 {@code application} 而不是 {@code infra}</h3>
 * <p>与 {@link SingleChannelRegistry} 同一条纪律（其 javadoc 记录了 2026-09-20 的 FIX-1）：
 * 本类<b>零 infra 依赖</b>，只用 {@link ChannelRegistry} / {@link ChannelRouter} /
 * {@link PaymentChannel} 三个应用层类型，是「门面」这个<b>应用层抽象</b>的退化实现，
 * 不是任何基础设施适配器。放在 {@code application} 才能让依赖方向恒为
 * {@code infra → application}，避免 Payment 侧兼容构造反向依赖 {@code channelgateway.infra..}
 * 而穿透包边界。</p>
 *
 * <h3>为什么允许 {@code registry} / {@code router} 为 null</h3>
 * <p>既有测试用「单通道兼容构造」直接注入一个 {@link PaymentChannel}；改造前那些类持有的是
 * 可空的 {@code channelRegistry} / {@code channelRouter}（{@code null} 表示「无注册表 ⇒ 跳过
 * 场景校验」「无 Router ⇒ 恒等回落」）。本实现保留同样的可空语义，让既有测试断言
 * <b>零修改</b>（NFR-2 / FR-036）。</p>
 */
@Component
public class DefaultChannelGateway implements ChannelGateway {

    /** spec 041：演示收银台截住扣款时给出的受理说明（不进日志正文以外的任何持久化）。 */
    private static final String DEFERRED_TO_CASHIER = "deferred to demo cashier";

    private final ChannelRegistry registry;
    private final ChannelRouter router;
    /**
     * 扣款派发策略（spec 041 / FR-021）：{@code null} 表示「无策略」——兼容构造下恒不截住，
     * 派发行为与改造前逐字一致。生产由 {@code DemoCashierDispatchPolicy} 装配。
     */
    private final ChargeDispatchPolicy dispatchPolicy;

    /**
     * 生产装配（Spring 唯一确定地选它）：注入注册表、路由器与派发策略。
     *
     * <p>{@code router} 允许为 {@code null}（部分兼容配置下无 Router）——缺失时按
     * 「无 Router」处理，与改造前逐字一致。</p>
     */
    @Autowired
    public DefaultChannelGateway(ChannelRegistry registry, ChannelRouter router,
                                 ObjectProvider<ChargeDispatchPolicy> dispatchPolicy) {
        this.registry = registry;
        this.router = router;
        this.dispatchPolicy = dispatchPolicy == null ? null : dispatchPolicy.getIfAvailable();
    }

    /**
     * 显式派发策略构造（测试与显式装配用）：不经过 {@code ObjectProvider}。
     */
    public DefaultChannelGateway(ChannelRegistry registry, ChannelRouter router,
                                 ChargeDispatchPolicy dispatchPolicy) {
        this.registry = registry;
        this.router = router;
        this.dispatchPolicy = dispatchPolicy;
    }

    /** 便捷构造：只有注册表、无 Router、无派发策略（兼容路径的显式写法）。 */
    public DefaultChannelGateway(ChannelRegistry registry, ChannelRouter router) {
        this(registry, router, (ChargeDispatchPolicy) null);
    }

    /** 便捷构造：只有注册表、无 Router（兼容路径的显式写法）。 */
    public DefaultChannelGateway(ChannelRegistry registry) {
        this(registry, null, (ChargeDispatchPolicy) null);
    }

    /**
     * 兼容构造：单通道（既有测试的注入形态）⇒ 包装为单通道注册表 + 恒等路由。
     *
     * <p>与改造前 {@code singleChannelRegistry(channel)} + {@code identityRouter(channel)}
     * 两个垫片的语义逐字等价。</p>
     */
    public DefaultChannelGateway(PaymentChannel singleChannel) {
        this(new SingleChannelRegistry(singleChannel), null);
    }

    @Override
    public String route(RouteContext context) {
        if (router != null) {
            return router.route(context);
        }
        // 兼容路径（无 Router）：显式码优先，缺省 MOCK —— 与改造前逐字一致
        String requested = context == null ? null : context.requestedChannelCode();
        return requested == null || requested.isBlank() ? "MOCK" : requested;
    }

    /**
     * 扣款：先经派发策略裁决，再决定是否触达渠道实现（spec 041 / FR-021）。
     *
     * <h3>为什么裁决在解析渠道<b>之前</b></h3>
     * <p>策略返回凭证意味着「平台尚未向渠道发起扣款」。若先解析再让渠道实现自己返回凭证，
     * 则渠道实现<b>已被触达</b>，与「未发起」自相矛盾（{@code PaymentDeferredChannelTest}
     * 断言延迟路径下渠道 MUST NOT 被调用）。故顺序固定为：裁决 →（不截住时）解析 → charge。</p>
     */
    @Override
    public ChannelResult pay(String channelCode, ChargeRequest request) {
        PayCredential deferred = deferredCredential(request);
        if (deferred != null) {
            // 不触达渠道实现：直接给「已受理、买家未付款」的凭证，payment 停 PROCESSING。
            return ChannelResult.accepted(null, DEFERRED_TO_CASHIER, deferred);
        }
        return requireRegistry().resolve(channelCode).charge(request);
    }

    /** 派发策略裁决；无策略（兼容构造）恒返回 {@code null}。 */
    private PayCredential deferredCredential(ChargeRequest request) {
        if (dispatchPolicy == null || request == null) {
            return null;
        }
        return dispatchPolicy.deferredCredential(request).orElse(null);
    }

    @Override
    public ChannelResult refund(String channelCode, RefundRequest request) {
        return requireRegistry().resolve(channelCode).refund(request);
    }

    @Override
    public ChannelResult query(String channelCode, QueryStatusRequest request) {
        return requireRegistry().resolve(channelCode).queryStatus(request);
    }

    @Override
    public ChannelResult refund(String channelCode, DyeMode mode, RefundRequest request) {
        return inMode(mode, () -> refund(channelCode, request));
    }

    @Override
    public ChannelResult query(String channelCode, DyeMode mode, QueryStatusRequest request) {
        return inMode(mode, () -> query(channelCode, request));
    }

    /**
     * 在指定模态下执行渠道调用（FR-013：模态判定的施加点收在网关域）。
     *
     * <p>{@code mode == null} ⇒ 不施加（沿用当前染色）。{@code DyeContext.callWith} 用
     * try/finally 恢复原值且<b>不吞异常</b>，故与改造前 Payment 侧自己包裹的语义逐字一致
     * （NFR-2）。</p>
     */
    private static <T> T inMode(DyeMode mode, Supplier<T> call) {
        return mode == null ? call.get() : DyeContext.callWith(mode, call);
    }

    @Override
    public Set<PaymentScene> supportedScenes(String channelCode) {
        if (registry == null) {
            return null; // 兼容路径：无注册表 ⇒ 调用方跳过场景校验（NFR-2）
        }
        return registry.resolve(channelCode).supportedScenes();
    }

    @Override
    public Set<String> registeredChannelCodes() {
        return requireRegistry().registeredCodes();
    }

    @Override
    public void requireRegistered(String channelCode) {
        // 委托解析即校验：未注册由注册表抛 INVALID_ARGUMENT（错误口径与 query 一致）
        requireRegistry().resolve(channelCode);
    }

    private ChannelRegistry requireRegistry() {
        if (registry == null) {
            throw new IllegalStateException(
                    "channel gateway has no registry: channel resolution requires a ChannelRegistry");
        }
        return registry;
    }
}
