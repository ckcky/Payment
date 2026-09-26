package com.payment.channelgateway.application;

import com.payment.channelgateway.domain.ChannelOrderRepository;

/**
 * 渠道单服务工厂（spec 041）：从「只有一个仓储」的既有注入形态造出
 * {@link ChannelOrderService}。
 *
 * <h3>为什么需要它</h3>
 * <p>改造前，单测与兼容构造只持有一个仓储对象（内存桩），而渠道单的写入口端口
 * 是后来（Feature 028）才从 payment 层划到渠道层的。为让既有测试
 * {@code new RefundAttemptSettlementService(repository)} 这类调用零改动，
 * 需要一条「把仓储当写入口用」的桥：内存桩
 * （{@code InMemoryChannelOrderRepository}）本就<b>同时实现</b>
 * {@link ChannelOrderRepository} 与 {@link ChannelOrderService}，故直接取回即可。</p>
 *
 * <p><b>生产路径不走这里</b>：生产用 {@code ChannelOrderServiceImpl}（持仓储、自带事务），
 * 由 Spring 装配；本工厂只服务测试与兼容构造。<b>因此它在 application 层不依赖 infra 实现</b>
 * ——传进来的必须是「两者兼备」的对象，否则是调用方装配错误，立即抛错而非静默降级。</p>
 */
public final class ChannelOrderServices {

    private ChannelOrderServices() {
    }

    /**
     * 把「两者兼备」的仓储取回为渠道单服务。
     *
     * @throws IllegalStateException 传进来的仓储未实现 {@link ChannelOrderService}
     *         ——说明调用方把生产仓储当成了写入口；生产仓储不是写入口，
     *         写入口是 {@code ChannelOrderServiceImpl}（含事务与唯一键语义），MUST NOT 冒充
     */
    public static ChannelOrderService of(ChannelOrderRepository repository) {
        if (repository instanceof ChannelOrderService service) {
            return service;
        }
        throw new IllegalStateException(
                "repository " + repository.getClass().getName() + " does not implement ChannelOrderService;"
                        + " the channel-order write port has its own implementation (ChannelOrderServiceImpl)"
                        + " carrying transaction + unique-key semantics — do not pass a plain repository");
    }
}
