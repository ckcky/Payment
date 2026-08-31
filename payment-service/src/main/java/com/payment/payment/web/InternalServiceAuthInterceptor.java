package com.payment.payment.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 内部服务间调用鉴权（ADR-0024）——<b>本期为空实现（占位）</b>。
 *
 * <p><b>负责人决议（2026-08-30）</b>：ADR-0024「内部服务间调用鉴权」改为<b>预留函数、空实现</b>；
 * ADR-0034~0037（出站令牌传播、入站鉴权推广、令牌轮换、鉴权失败可观测性）一并<b>先不做</b>。
 * 因此本拦截器仍挂在 {@code /internal/**} 上，作为鉴权的<b>唯一接入点</b>，但当前恒定放行。</p>
 *
 * <p>已随本次决议移除的实现：{@code X-Service-Token} 常数时间比对、未配置 {@code 503} /
 * 不匹配 {@code 403}、{@code payment.internal_auth_rejected} 埋点，以及出站侧的
 * {@code InternalTokenRequestInterceptor}、{@code FeignInternalTokenAutoConfiguration}
 * 与 {@code platform.security.internal-token} 配置。取舍记录见
 * {@code docs/adr/0011-internal-token-decisions.md}。</p>
 *
 * <p><b>未来接入真实鉴权时只需改 {@link #verifyServiceToken}</b>：读取 {@code X-Service-Token}
 * 与配置令牌做常数时间比对，未配置返回 {@code 503}、缺失或不匹配返回 {@code 403}；
 * 并<b>必须同时</b>在调用方补出站令牌，否则会全线 {@code 403}（ADR-0034 记录的拓扑约束）。</p>
 */
@Component
public class InternalServiceAuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        return verifyServiceToken(request);
    }

    /**
     * 预留：内部服务令牌校验。
     *
     * <p>ADR-0024 / ADR-0034~0037 决议本期不做，故为<b>空实现、恒放行</b>。
     * 保留此方法是为了让鉴权逻辑有唯一归口，将来不必散落到各 Controller。</p>
     *
     * @return 恒 {@code true}（放行）
     */
    private boolean verifyServiceToken(HttpServletRequest request) {
        // TODO(ADR-0024)：接入真实鉴权时在此实现。当前按负责人决议留空，恒定放行。
        return true;
    }
}
