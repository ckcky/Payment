package com.payment.common.mq;

/**
 * 消息通道异常（spec 029）。
 *
 * <p>属系统级异常（非业务错误）：通道不可用时生产方按 {@code mq.enabled} 决定回落同步调用
 * 或冒泡失败；消费端捕获后走重试/DLQ，不得吞掉。</p>
 */
public class MqException extends RuntimeException {

    public MqException(String message) {
        super(message);
    }

    public MqException(String message, Throwable cause) {
        super(message, cause);
    }
}
