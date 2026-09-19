package com.payment.payment.mq;

import java.util.function.Supplier;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 单测桩（spec 029 / T39）：构造 {@code ObjectProvider<PaymentEventPublisher>}。
 *
 * <p>{@link #off()} 模拟 {@code mq.enabled=false}（provider 无 Bean → 应用服务/处理器走
 * FR-306 同步回落）；{@link #provider(PaymentEventPublisher)} 注入替身以断言 MQ 路径。</p>
 */
public final class MqTestSupport {

    private MqTestSupport() {
    }

    /** MQ 关闭：{@code getIfAvailable()} 返回 null。 */
    public static ObjectProvider<PaymentEventPublisher> off() {
        return provider(() -> null);
    }

    /** 以给定发布器构造 provider。 */
    public static ObjectProvider<PaymentEventPublisher> provider(PaymentEventPublisher publisher) {
        return provider(() -> publisher);
    }

    private static ObjectProvider<PaymentEventPublisher> provider(Supplier<PaymentEventPublisher> supplier) {
        return new ObjectProvider<>() {
            @Override
            public PaymentEventPublisher getObject() {
                PaymentEventPublisher p = supplier.get();
                if (p == null) {
                    throw new BeansException("no PaymentEventPublisher available") {
                    };
                }
                return p;
            }

            @Override
            public PaymentEventPublisher getObject(Object... args) {
                return getObject();
            }

            @Override
            public PaymentEventPublisher getIfAvailable() {
                return supplier.get();
            }

            @Override
            public PaymentEventPublisher getIfUnique() {
                return supplier.get();
            }
        };
    }
}
