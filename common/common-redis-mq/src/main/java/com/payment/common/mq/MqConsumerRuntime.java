package com.payment.common.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 消费端运行时（spec 029 / T20-T23 装配）：在应用启动后为每个 {@link StreamConsumer}
 * 拉一条守护线程跑消费循环，应用关闭时优雅停止。
 *
 * <p>各服务只需构造 {@link StreamConsumer} 若干并注册进来。</p>
 */
public class MqConsumerRuntime implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(MqConsumerRuntime.class);

    private final List<StreamConsumer> consumers = new ArrayList<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mq-consumer");
        t.setDaemon(true);
        return t;
    });

    /** 注册一个消费者（其后由 {@link #afterPropertiesSet()} 统一启动）。 */
    public MqConsumerRuntime register(StreamConsumer consumer) {
        if (consumer != null) {
            consumers.add(consumer);
        }
        return this;
    }

    @Override
    public void afterPropertiesSet() {
        for (StreamConsumer c : consumers) {
            executor.submit(c::start);
            log.info("MQ 消费者线程已提交");
        }
    }

    @Override
    public void destroy() {
        for (StreamConsumer c : consumers) {
            c.stop();
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
