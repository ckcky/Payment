package com.payment.e2e.support;

import org.awaitility.Awaitility;
import org.awaitility.core.ConditionEvaluationListener;
import org.awaitility.core.EvaluatedCondition;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;

/**
 * 统一轮询等待（spec 022 / T407，FR-005 / NFR-004）：
 * Awaitility 封装——**全模块禁用 Thread.sleep**，最终一致收敛统一走本类。
 * 默认超时 15s（{@link Env#awaitTimeoutMs()} 可配），间隔 200ms。
 */
public final class Await {

    /** 轮询至条件成立；超时抛 AwaitilityException（含最后状态描述）。 */
    public static void until(String description, Callable<Boolean> condition) {
        Awaitility.await(description)
                .atMost(Duration.ofMillis(Env.awaitTimeoutMs()))
                .pollInterval(Duration.ofMillis(200))
                .conditionEvaluationListener(new FailFastLogger())
                .until(condition);
    }

    /** 便捷重载：直接传 BooleanSupplier。 */
    public static void until(String description, BooleanSupplier supplier) {
        until(description, supplier::getAsBoolean);
    }

    /** 轮询至动作执行不再抛异常（用于「等状态可查询」类收敛）。 */
    public static void untilNoException(String description, Runnable action) {
        Awaitility.await(description)
                .atMost(Duration.ofMillis(Env.awaitTimeoutMs()))
                .pollInterval(Duration.ofMillis(200))
                .ignoreExceptions()
                .until(() -> {
                    action.run();
                    return true;
                });
    }

    /** 失败时记录最后评估结果（配合 Dump 落盘定位）。 */
    private static final class FailFastLogger implements ConditionEvaluationListener<Boolean> {
        @Override
        public void conditionEvaluated(EvaluatedCondition<Boolean> condition) {
            // 轮询过程静默；超时异常由 Awaitility 抛出并携带描述
        }
    }

    private Await() {
    }
}
