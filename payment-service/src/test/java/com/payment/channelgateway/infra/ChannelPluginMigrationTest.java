package com.payment.channelgateway.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.channelgateway.application.spi.AbstractChannelPlugin;
import com.payment.channelgateway.application.spi.ChannelPluginDescriptor;
import com.payment.channelgateway.infra.wechat.WechatChannelPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * spec 037 / T6 / FR-014：存量渠道 MUST 迁移到 {@link AbstractChannelPlugin}。
 *
 * <h3>为什么断言的是「可赋值」而不是「直接父类」</h3>
 * FR-014 要求四家渠道都成为插件一族。但四家渠道<b>共用</b>一个 mock 语义基类
 * （{@code AbstractMockChannelAdapter}），把它整个删掉、让每个 Adapter 各自重写一遍
 * 「金额尾数注入 + 退款异步推送」是<b>纯粹的复制</b>——三份同源代码必然漂移，
 * 而 spec 022 的 E2E 断言恰恰依赖「全渠道同一金额触发同一结果」。
 *
 * <p>故迁移落点是：让 {@code AbstractMockChannelAdapter} 本身成为
 * {@link AbstractChannelPlugin} 的子类（吸收模板方法四步），三个 Adapter
 * <b>零改动</b>地（间接）成为插件。判据因此是 {@code isAssignableFrom}——
 * 它检验的是「是不是插件」，而不是「父类名字叫什么」。</p>
 *
 * <h3>WECHAT 为何在断言里</h3>
 * 微信由 spec 039 一步到位迁为插件（{@code WechatChannelPlugin}），已随 039 合入 master。
 * 本类把它一并断言，是为了让「四家都已是插件」这条 FR-014 判据在<b>一处</b>可读——
 * 而不是「三家在本 Spec 迁、一家在别处迁」的分散事实。
 */
class ChannelPluginMigrationTest {

    @Test
    @DisplayName("MOCK / ALIPAY / DOUYIN / WECHAT 四家渠道均已是 AbstractChannelPlugin 一族 [FR-014]")
    void allFourLegacyChannelsArePlugins() {
        assertThat(AbstractChannelPlugin.class)
                .as("MOCK 渠道（未指定渠道时的默认渠道）")
                .isAssignableFrom(MockChannelAdapter.class);
        assertThat(AbstractChannelPlugin.class)
                .as("ALIPAY 渠道（单 Adapter 双模态）")
                .isAssignableFrom(AlipayChannelAdapter.class);
        assertThat(AbstractChannelPlugin.class)
                .as("DOUYIN 渠道")
                .isAssignableFrom(DouyinChannelAdapter.class);
        assertThat(AbstractChannelPlugin.class)
                .as("WECHAT 渠道（spec 039 迁入）")
                .isAssignableFrom(WechatChannelPlugin.class);
    }

    @Test
    @DisplayName("三家本 Spec 迁移的渠道均提供插件自描述，且 code 与 channelCode 单一事实源一致 [FR-014]")
    void migratedChannelsSelfDescribe() {
        assertDescriptor(new MockChannelAdapter(), "MOCK");
        assertDescriptor(new AlipayChannelAdapter(AlipayChannelAdapter.Scenario.SUCCESS), "ALIPAY");
        assertDescriptor(new DouyinChannelAdapter(DouyinChannelAdapter.Scenario.SUCCESS), "DOUYIN");
    }

    private static void assertDescriptor(MockChannelAdapter channel, String expectedCode) {
        ChannelPluginDescriptor descriptor = channel.descriptor();
        assertThat(descriptor).as("插件 MUST 提供自描述").isNotNull();
        assertThat(descriptor.code()).isEqualTo(expectedCode);
        // channelCode() 的默认实现即 descriptor().code()——两者不得漂移（SPI-01）
        assertThat(channel.channelCode()).isEqualTo(descriptor.code());
        assertThat(descriptor.supportedScenes()).as("描述符的 supportedScenes 不得为空").isNotEmpty();
    }

    private static void assertDescriptor(AlipayChannelAdapter channel, String expectedCode) {
        ChannelPluginDescriptor descriptor = channel.descriptor();
        assertThat(descriptor).isNotNull();
        assertThat(descriptor.code()).isEqualTo(expectedCode);
        assertThat(channel.channelCode()).isEqualTo(descriptor.code());
        assertThat(descriptor.supportedScenes()).isNotEmpty();
    }

    private static void assertDescriptor(DouyinChannelAdapter channel, String expectedCode) {
        ChannelPluginDescriptor descriptor = channel.descriptor();
        assertThat(descriptor).isNotNull();
        assertThat(descriptor.code()).isEqualTo(expectedCode);
        assertThat(channel.channelCode()).isEqualTo(descriptor.code());
        assertThat(descriptor.supportedScenes()).isNotEmpty();
    }

    @Test
    @DisplayName("回调挂载点由插件自描述声明：ALIPAY 有、纯 mock 渠道没有 [SPI-01 / FR-014]")
    void callbackPathIsSelfDescribed() {
        // 支付宝有专属异步通知协议 ⇒ 必须声明回调路径（删除 AlipayNotifyController 后走通用端点）
        assertThat(new AlipayChannelAdapter(AlipayChannelAdapter.Scenario.SUCCESS).descriptor().callbackPath())
                .as("支付宝 MUST 声明回调路径")
                .isNotBlank();
        // 纯 mock 渠道的结果由进程内推送（PaymentNotifyPort），不经 HTTP 回调
        assertThat(new MockChannelAdapter().descriptor().callbackPath())
                .as("纯 mock 渠道 MUST NOT 声明回调路径")
                .isNull();
        assertThat(new DouyinChannelAdapter(DouyinChannelAdapter.Scenario.SUCCESS).descriptor().callbackPath())
                .as("纯 mock 渠道 MUST NOT 声明回调路径")
                .isNull();
    }
}
