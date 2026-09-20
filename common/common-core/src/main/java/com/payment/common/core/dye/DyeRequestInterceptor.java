package com.payment.common.core.dye;

import feign.RequestInterceptor;
import feign.RequestTemplate;

/**
 * 染色出站拦截器（spec 030 / ADR-0076，FR-163）：Feign 调用时把当前模态写入
 * {@code X-Dye-Tag}，供下游服务的 {@link DyeFilter} 读取。
 *
 * <p><b>非空才写</b>：未染色（{@code null}）时<b>不写头</b>——不把「缺省 = MOCK」
 * 这个语义硬编码进线上协议。否则一旦将来缺省语义变更，已部署实例的出站头会与
 * 新语义相互矛盾，且排查时无法区分「显式 MOCK」与「没染色」。</p>
 *
 * <h3>INV-5：与 {@link DyeFilter} MUST 同批落地</h3>
 * 只做入站不做出站 ⇒ 下游全线按未染色处理，跨服务沙箱链路直接断掉。
 */
public class DyeRequestInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        DyeMode mode = DyeContext.current();
        if (mode == null) {
            return; // 未染色 ⇒ 不写头（不把缺省语义硬编码进协议）
        }
        template.header(DyeFilter.DYE_HEADER, mode.name());
    }
}
