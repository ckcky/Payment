package com.payment.channelgateway.application.spi;

import com.payment.channelgateway.application.PaymentChannel;
import com.payment.common.dto.channel.PaymentScene;

import java.util.Set;

/**
 * 渠道<b>插件</b>端口（渠道插件化内核 / SPI-04）：{@link PaymentChannel} 的插件化扩展。
 *
 * <h3>与 {@link PaymentChannel} 的分工</h3>
 * {@code PaymentChannel} 是<b>能力契约</b>（能扣款、能退款、能查询）；
 * 本接口补上<b>插件契约</b>——「你是谁、你能干什么、你的回调怎么翻译」。
 * 区分二者的意义在于：能力契约从第一天就有，插件契约是本次微内核改造新增的。
 * 二者分离让既有渠道（{@code AbstractMockChannelAdapter} 一族）<b>零改动</b>继续工作，
 * 新渠道则按插件范式接入。<b>刻意不做强制迁移</b>——一次性重写 4 个 Adapter
 * 会把「架构演进」变成「全渠道回归测试」，收益不成比例。</p>
 *
 * <h3>自描述取代 instanceof（SPI-01 的核心）</h3>
 * 注册表以前只有一个 {@code channelCode()} 字符串，于是「这个渠道支不支持 H5」
 * 「它的回调挂哪」只能靠 {@code if (code.equals("ALIPAY"))} 回答——那等于把渠道差异
 * 写回内核，接第 N 家渠道就要改第 N 次内核。现在由插件自己声明
 * {@link ChannelPluginDescriptor}，内核只做泛型调度。</p>
 *
 * <h3>{@code channelCode()} 的唯一事实源</h3>
 * 本接口把 {@code channelCode()} 默认实现为 {@code descriptor().code()}：
 * 渠道身份在描述符里<b>只有一处</b>定义，杜绝「描述符写 STRIPE、{@code channelCode()}
 * 却返回 stripe」这类漂移。</p>
 */
public interface ChannelPlugin extends PaymentChannel {

    /** 插件自描述（身份 / 能力 / 回调挂载点）。每个插件 MUST 提供，且 code 全局唯一。 */
    ChannelPluginDescriptor descriptor();

    @Override
    default String channelCode() {
        return descriptor().code();
    }

    @Override
    default Set<PaymentScene> supportedScenes() {
        return descriptor().supportedScenes();
    }

    @Override
    default boolean supportsRealMode() {
        return descriptor().supportsRealMode();
    }

    /**
     * 是否接收渠道异步回调（由描述符的 {@code callbackPath} 决定）。
     *
     * <p>纯 mock 插件与「只能靠主动查询收敛」的渠道返回 {@code false}——
     * 通用回调端点据此直接拒收，而不是先解析再报错。</p>
     */
    default boolean acceptsCallback() {
        return descriptor().callbackPath() != null && !descriptor().callbackPath().isBlank();
    }

    /**
     * 把渠道私有回调报文翻译成平台语义（SPI-02 的落点）。
     *
     * <p><b>验签 MUST 在本方法内完成</b>（ADR-0025 / FR-202）：签名未验证之前，报文里的
     * 任何字段都不可信。验签失败 MUST 抛异常——由通用回调端点转成 403，
     * <b>不触达</b>任何状态推进逻辑（INV-10）。</p>
     *
     * <p><b>本方法不得产生副作用</b>：它只做「翻译」，状态的推进由内核的
     * {@code PaymentCallbackService} 负责。把两者混在一起会让「解析成功但收敛失败」
     * 这种中间态无处表达。</p>
     *
     * @param envelope 原始回调信封（内核未做任何渠道语义解析）
     * @return 解析产物
     * @throws com.payment.common.core.error.BizException 验签失败 / 报文不可解析
     */
    default ParsedCallback parseCallback(ChannelCallbackEnvelope envelope) {
        throw new UnsupportedOperationException(
                "channel '" + channelCode() + "' does not accept callbacks "
                        + "(descriptor.callbackPath is null); do not route callbacks to it");
    }

    /**
     * 回调成功应答体（渠道协议要求）。
     *
     * <p>支付宝靠<b>字符串精确匹配</b> {@code success} 判断「平台已收到」，多一个引号或换行
     * 都会被判为未收到而反复重推；Stripe 只认 HTTP 200，body 内容无所谓。
     * 差异由插件自己声明，内核不写死。</p>
     */
    default String callbackAckBody() {
        return "success";
    }
}
