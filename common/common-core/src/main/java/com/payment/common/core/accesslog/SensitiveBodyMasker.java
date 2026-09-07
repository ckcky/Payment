package com.payment.common.core.accesslog;

/**
 * 报文脱敏钩子（spec 021 / D3，ADR-0068）：访问日志落盘前对请求/响应报文的唯一处理入口。
 *
 * <p>本期只留桩（负责人拍板：不真脱敏）；将来启用 JSON 字段级 mask、二进制跳过等，
 * 只需替换实现 Bean（{@code @ConditionalOnMissingBean} 允许服务覆盖），Filter 与各服务零改动。</p>
 *
 * <p>签名含 {@code contentType}：内容类型判断（如 {@code application/json} 才做字段级脱敏、
 * 二进制直接占位）由实现内解决（NFR-003）。</p>
 */
public interface SensitiveBodyMasker {

    /**
     * @param contentType 请求/响应的 Content-Type（可能为 null）
     * @param body        原始报文（UTF-8 文本；二进制/ multipart 已由 Filter 占位，不进入本钩子）
     * @return 脱敏后的报文（默认透传实现原样返回）
     */
    String mask(String contentType, String body);
}
