package com.payment.common.core.dye;

import static org.assertj.core.api.Assertions.assertThat;

import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link DyeRequestInterceptor} 出站测试（spec 030 / T116 / FR-163，SC-A-10）。
 *
 * <p>核心断言：<b>非空才写</b>——未染色时<b>不写头</b>，不把「缺省 = MOCK」这个语义
 * 硬编码进线上协议。</p>
 */
class DyeRequestInterceptorTest {

    private final DyeRequestInterceptor interceptor = new DyeRequestInterceptor();

    @AfterEach
    void tearDown() {
        DyeContext.clear();
    }

    @Test
    void writesHeaderWhenDyed() {
        DyeContext.set(DyeMode.SANDBOX);

        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);

        assertThat(template.headers().get(DyeFilter.DYE_HEADER))
                .containsExactly(DyeMode.SANDBOX.name());
    }

    @Test
    void doesNotWriteHeaderWhenNotDyed() {
        DyeContext.clear(); // 未染色

        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);

        // 关键：不写头——缺省语义不进协议
        assertThat(template.headers()).doesNotContainKey(DyeFilter.DYE_HEADER);
        assertThat(template.headers()).isEmpty();
    }
}
