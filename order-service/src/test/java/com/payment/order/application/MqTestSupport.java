package com.payment.order.application;

import com.payment.order.mq.OrderEventPublisher;
import java.util.function.Supplier;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 单测桩（spec 029 / T39）：构造 {@code ObjectProvider<OrderEventPublisher>}。
 *
 * <p>两个使用面：</p>
 * <ul>
 *   <li>{@link #off()} —— 模拟 {@code mq.enabled=false}：provider 无 Bean，应用服务走 FR-306 同步回落；</li>
 *   <li>自定义 supplier —— 模拟开启：注入替身发布器断言「本地事实落定后按 MQ 发布、不再同步调 Feign」。</li>
 * </ul>
 */
public final class MqTestSupport {

    private MqTestSupport() {
    }

    /** MQ 关闭：{@code getIfAvailable()} 返回 null（应用服务回落同步 Feign）。 */
    public static ObjectProvider<OrderEventPublisher> off() {
        return provider(() -> null);
    }

    /** 以给定发布器构造 provider（断言 MQ 路径用）。 */
    public static ObjectProvider<OrderEventPublisher> provider(OrderEventPublisher publisher) {
        return provider(() -> publisher);
    }

    private static ObjectProvider<OrderEventPublisher> provider(Supplier<OrderEventPublisher> supplier) {
        return new ObjectProvider<>() {
            @Override
            public OrderEventPublisher getObject() {
                OrderEventPublisher p = supplier.get();
                if (p == null) {
                    throw new BeansException("no OrderEventPublisher available") {
                    };
                }
                return p;
            }

            @Override
            public OrderEventPublisher getObject(Object... args) {
                return getObject();
            }

            @Override
            public OrderEventPublisher getIfAvailable() {
                return supplier.get();
            }

            @Override
            public OrderEventPublisher getIfUnique() {
                return supplier.get();
            }
        };
    }
}
