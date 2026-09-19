package com.payment.common.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;

/**
 * MQ 阻塞读超时不变量守卫（spec 029 回归修复）。
 *
 * <p><b>为什么需要</b>：{@link StreamConsumer} 的拉取是
 * {@code XREADGROUP ... BLOCK blockMs}。若 Redis 客户端命令超时
 * （{@code spring.data.redis.timeout}）**不大于** {@code blockMs}，则该命令必然
 * 在「服务端正常阻塞等待」期间被客户端判超时，抛
 * {@code RedisCommandTimeoutException}；消费循环每 1s 重试一次、永远读不到消息，
 * 表现为「支付成功但订单永不收敛为 PAID」，且**没有任何业务异常日志**——极难定位。</p>
 *
 * <p><b>为什么单测发现不了</b>：{@code StreamConsumerTest} 用
 * {@code new LettuceConnectionFactory(host, port)}（Lettuce 默认 60s 超时），
 * 2000ms 阻塞读可正常完成；只有显式配了 1s 超时的真实服务才复现。</p>
 *
 * <p>本守卫在启动期校验「客户端超时 &gt; blockMs」，不满足即 fail-fast 中止启动，
 * 把静默挂死变成显式、可操作的启动失败。</p>
 *
 * <p>由 {@link RedisMqAutoConfiguration} 在 {@code payment.mq.enabled=true} 时注册。</p>
 */
public class MqTimeoutGuard implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(MqTimeoutGuard.class);

    /** 最小安全余量：客户端超时至少要比阻塞时长多这么多。 */
    private static final Duration MIN_MARGIN = Duration.ofMillis(500);

    private final MqProperties props;
    private final Duration redisCommandTimeout;

    public MqTimeoutGuard(MqProperties props,
                          @Value("${spring.data.redis.timeout:60s}") Duration redisCommandTimeout) {
        this.props = props;
        this.redisCommandTimeout = redisCommandTimeout;
    }

    @Override
    public void afterPropertiesSet() {
        Duration block = props.getBlockMs();
        if (redisCommandTimeout.compareTo(block.plus(MIN_MARGIN)) < 0) {
            throw new IllegalStateException(String.format(
                    "MQ 阻塞读超时配置非法：spring.data.redis.timeout=%dms 必须大于 "
                            + "payment.mq.block-ms=%dms（至少多 %dms）。否则每轮 XREADGROUP 都会"
                            + "被客户端判超时，消费端空转、消息永不被处理（spec 029 回归缺陷）。"
                            + "请上调 spring.data.redis.timeout（建议 %dms 以上）或下调 block-ms。",
                    redisCommandTimeout.toMillis(), block.toMillis(), MIN_MARGIN.toMillis(),
                    block.plus(MIN_MARGIN).toMillis()));
        }
        log.info("MQ 阻塞读超时校验通过：redis.timeout={}ms > block-ms={}ms",
                redisCommandTimeout.toMillis(), block.toMillis());
    }
}
