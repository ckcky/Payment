package com.payment.channelgateway.application;

import com.payment.common.dto.channel.PaymentScene;

import java.util.Set;

/**
 * 渠道网关门面（spec 037 / FR-007 / FR-008 / INV-1 / SC-002）。
 *
 * <p>把渠道网关做成<b>进程内微服务</b>：Payment 侧只面对这一个入口，
 * {@link ChannelRegistry} / {@link ChannelRouter} / {@link PaymentChannel}（及其插件化扩展）
 * 成为渠道网关域的<b>私有实现</b>——Payment 不再自己拼装「先路由、再取实现」。</p>
 *
 * <h3>为什么需要门面（改造前的形态）</h3>
 * <p>改造前 Payment 侧有 6 处自己写 {@code channelRegistry.resolve(channelCode)}，
 * 意味着「渠道网关内部是 Router + Registry + Plugin 三件套」这件事对 Payment
 * <b>完全透明</b>——那不是微服务边界，只是「同层代码分了个包」：网关内核一改，
 * Payment 跟着改。</p>
 *
 * <p><b>与 038 的关系</b>：038 把渠道网关件整体搬进顶层包 {@code com.payment.channelgateway..}，
 * 建立了<b>物理包边界</b>；本门面在此之上补<b>调用边界</b>——包边界只保证「Payment 不编译依赖
 * 渠道实现类」这一件事需要靠人守，门面把「Payment 能用渠道域做什么」收敛成 6 个方法。</p>
 *
 * <h3>方法族（FR-007 的三个核心方法 + 能力查询）</h3>
 * <ul>
 *   <li>{@link #pay} / {@link #refund} / {@link #query}：三个核心调用入口；</li>
 *   <li>{@link #route}：正向选路（只产出渠道码，不接触渠道协议）——Payment 需要在<b>建单之前</b>
 *       拿到最终渠道码参与幂等键与落库（FR-023），故选路也必须经门面；</li>
 *   <li>{@link #supportedScenes} / {@link #registeredChannelCodes}：编排层与只读管理端点需要的
 *       能力查询。</li>
 * </ul>
 *
 * <h3>行为零变化（NFR-2）</h3>
 * <p>本门面是<b>机械替换</b>：解析语义、未注册时的错误、路由回落规则与改造前逐字一致。
 * 既有单测断言零修改。</p>
 */
public interface ChannelGateway {

    /**
     * 正向选路：给定上下文选出一个<b>已注册</b>的渠道码（大写）。
     *
     * <p>只产出 {@code channelCode} 字符串，不接触渠道协议。无 Router（兼容构造）时回落为
     * 「调用方显式指定的渠道码，缺省 {@code MOCK}」——与改造前
     * {@code PaymentApplicationService#resolveChannelCode} 的兼容分支逐字一致。</p>
     */
    String route(RouteContext context);

    /** 扣款：按<b>调用方给出的</b>渠道码精确解析渠道实现后调用（门面不选路）。 */
    ChannelResult pay(String channelCode, ChargeRequest request);

    /**
     * 退款：按<b>调用方给出的</b>渠道码精确解析渠道实现后调用。
     *
     * <p><b>INV-6</b>：退款是反向路径，渠道码来自 {@code payment_attempts.channel_code} 已记录的值，
     * 门面 <b>MUST NOT</b> 重新选路——退款换渠道 = 钱退错地方。</p>
     */
    ChannelResult refund(String channelCode, RefundRequest request);

    /** 主动查询：按<b>已记录</b>的渠道码精确解析渠道实现后调用（INV-6，同样禁止重新选路）。 */
    ChannelResult query(String channelCode, QueryStatusRequest request);

    /**
     * 该渠道支持的支付场景。
     *
     * <p><b>兼容路径返回 {@code null}</b>：无注册表时（既有测试的兼容构造）无法回答能力问题，
     * 调用方据此<b>跳过</b>场景校验——与改造前 {@code channelRegistry == null} 的判据逐字等价
     * （NFR-2：不新增行为）。</p>
     */
    Set<PaymentScene> supportedScenes(String channelCode);

    /** 当前已注册的全部渠道码（大写、不可变；只读管理端点与错误信息拼装用）。 */
    Set<String> registeredChannelCodes();

    /**
     * 反向路径的<b>先验校验</b>（INV-6）：确认该渠道码可解析，未注册即抛
     * {@code INVALID_ARGUMENT}（与 {@link #query} 同一错误口径）。
     *
     * <p><b>为什么需要单独一个校验方法</b>：{@code ChannelQueryService#queryOnce} 必须在
     * <b>消耗一次查询次数之前</b>确认「渠道码可解析」——{@code payment_attempts} 行存在但
     * {@code channel_code} 已未注册（渠道下线/配置变更）属于脏数据，它 MUST NOT 计入
     * {@code payments.query_attempts}，否则脏数据会自己把查询预算耗光、掩盖真实原因。
     * 若把校验推迟到 {@link #query} 内部，那次计数就已经落库了。</p>
     *
     * <p>解析本身仍由 {@link #query} 在真正调用时完成；本方法只回答「可不可以」。</p>
     */
    void requireRegistered(String channelCode);

    // ---------- 兼容构造工厂（既有测试的注入形态，NFR-2 零改动） ----------

    /**
     * 兼容路径：<b>无注册表、无 Router</b> 的门面。
     *
     * <p>语义与改造前「{@code channelRegistry == null} + {@code channelRouter == null}」逐字等价：
     * {@link #route} 用显式码、缺省 {@code MOCK}；{@link #supportedScenes} 返回 {@code null}
     * 让调用方跳过场景校验。三通道方法在此形态下无意义（会抛
     * {@link IllegalStateException}）——既有该路径的渠道调用并不经门面，而是走
     * {@code PaymentRetryService}。</p>
     */
    static ChannelGateway none() {
        return new DefaultChannelGateway((ChannelRegistry) null, (ChannelRouter) null);
    }

    /**
     * 兼容路径：<b>单通道</b>门面（既有测试只关心一个渠道）。
     *
     * <p>语义与改造前 {@code singleChannelRegistry(channel)} + {@code identityRouter(channel)}
     * 两个垫片等价。</p>
     */
    static ChannelGateway ofSingleChannel(PaymentChannel singleChannel) {
        return new DefaultChannelGateway(singleChannel);
    }
}
