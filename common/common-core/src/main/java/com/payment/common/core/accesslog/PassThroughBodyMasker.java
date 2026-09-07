package com.payment.common.core.accesslog;

/**
 * 透传脱敏桩（spec 021 / D3）：原样返回，不改任何内容——真脱敏启用前的占位实现。
 */
public class PassThroughBodyMasker implements SensitiveBodyMasker {

    @Override
    public String mask(String contentType, String body) {
        return body;
    }
}
