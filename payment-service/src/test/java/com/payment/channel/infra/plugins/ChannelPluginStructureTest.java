package com.payment.channel.infra.plugins;

import com.payment.channel.application.spi.AbstractChannelPlugin;
import com.payment.channel.infra.plugins.alipay.AlipayChannelAdapter;
import com.payment.channel.infra.plugins.douyin.DouyinChannelAdapter;
import com.payment.channel.infra.plugins.mock.MockChannelAdapter;
import com.payment.channel.infra.plugins.stripe.StripeChannelPlugin;
import com.payment.channel.infra.plugins.wechat.WechatChannelPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 插件目录结构测试（spec 041 / T10 验收：插件结构测试；FR-007 / FR-008 / INV-009）。
 *
 * <p>钉住两件事：
 * <ol>
 *   <li><b>FR-007 目录自包含</b>：五家渠道的插件实现全部落在
 *       {@code channel.infra.plugins.<vendor>} 下——「在唯一 plugins/&lt;channel&gt;/ 目录找到
 *       该渠道全部实现」是 US-09 的验收口径；</li>
 *   <li><b>FR-008 模板方法归位</b>：通用步骤的模板（{@link AbstractChannelPlugin}）在
 *       {@code application.spi}（内核侧），插件侧只有差异——依赖方向恒为
 *       {@code plugins → application.spi}，反向即内核被插件绑架。</li>
 * </ol>
 */
class ChannelPluginStructureTest {

    @Test
    @DisplayName("FR-007：五家渠道实现全部位于 channel.infra.plugins.. 之下")
    void allChannelImplementationsResideUnderPlugins() {
        assertPackage(AlipayChannelAdapter.class, "com.payment.channel.infra.plugins.alipay");
        assertPackage(DouyinChannelAdapter.class, "com.payment.channel.infra.plugins.douyin");
        assertPackage(MockChannelAdapter.class, "com.payment.channel.infra.plugins.mock");
        assertPackage(StripeChannelPlugin.class, "com.payment.channel.infra.plugins.stripe");
        assertPackage(WechatChannelPlugin.class, "com.payment.channel.infra.plugins.wechat");
    }

    @Test
    @DisplayName("FR-008：模板方法（AbstractChannelPlugin）在内核 application.spi，不在任何插件目录")
    void templateMethodResidesInKernelSpi() {
        assertThat(AbstractChannelPlugin.class.getPackageName())
                .as("通用步骤模板必须归内核——落到插件目录就会被某家渠道私有覆写，模板名存实亡")
                .isEqualTo("com.payment.channel.application.spi");
    }

    @Test
    @DisplayName("FR-008：插件是一族——五家实现均可赋值到 AbstractChannelPlugin（差异关进笼子）")
    void everyChannelIsAssignableToTemplate() {
        assertThat(AbstractChannelPlugin.class).isAssignableFrom(AlipayChannelAdapter.class);
        assertThat(AbstractChannelPlugin.class).isAssignableFrom(DouyinChannelAdapter.class);
        assertThat(AbstractChannelPlugin.class).isAssignableFrom(MockChannelAdapter.class);
        assertThat(AbstractChannelPlugin.class).isAssignableFrom(StripeChannelPlugin.class);
        assertThat(AbstractChannelPlugin.class).isAssignableFrom(WechatChannelPlugin.class);
    }

    private static void assertPackage(Class<?> type, String expectedPackage) {
        assertThat(type.getPackageName())
                .as("%s 必须落在 %s（FR-007 目录自包含）", type.getSimpleName(), expectedPackage)
                .isEqualTo(expectedPackage);
    }
}
