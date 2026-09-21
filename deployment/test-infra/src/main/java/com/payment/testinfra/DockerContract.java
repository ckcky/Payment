package com.payment.testinfra;

import java.util.function.BooleanSupplier;
import org.opentest4j.TestAbortedException;

/**
 * Docker 可用性契约（spec 033 §5.2 C-4 / §13.3 禁假绿① + 禁假红）。
 *
 * <p>真库测试在无 Docker 环境下的行为由本类<b>唯一裁决</b>：</p>
 * <ul>
 *   <li><b>本地（默认）</b>：{@code skip}——但不许静默，必须打印带类名的 WARN 行，
 *       让「N 个真库测试被跳过」在输出里可见（spec §12#2）；</li>
 *   <li><b>CI（{@code -Drealdb.required=true}）</b>：{@code fail}——「全绿但其实没跑」是
 *       spec 033 §0 判据的直接对立面，skip 即红（禁假绿①）。</li>
 * </ul>
 *
 * <p>决策本体抽成 {@link #decide(boolean, boolean)} 纯函数以便单测；探测器可替换
 * （包内可见），使本类在无 Docker 的 CI 上也能被单元测试。</p>
 */
public final class DockerContract {

    /** CI 强制执行开关：设为 {@code true} 时无 Docker 即 fail 而非 skip（verify.yml real-db job 使用）。 */
    public static final String REQUIRED_PROPERTY = "realdb.required";

    /** 默认探测器：Testcontainers 官方探测（失败/异常一律视为不可用）。 */
    private static volatile BooleanSupplier probe = () -> {
        try {
            return org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable unavailable) {
            return false;
        }
    };

    private DockerContract() {
    }

    /**
     * 裁决并放行或中止：Docker 可用 → 正常返回；不可用 → 本地 {@code Assumptions.abort}
     * （类级 skip，WARN 可见）或 required 模式下抛 {@link IllegalStateException}（skip 即红）。
     */
    public static void ensureAvailableOrSkip(Class<?> testClass) {
        boolean required = Boolean.getBoolean(REQUIRED_PROPERTY);
        decide(probe.getAsBoolean(), required);
    }

    /** 决策纯函数：可用 → 返回；不可用且 required → 抛 IllegalStateException；不可用且非 required → abort。 */
    static void decide(boolean dockerAvailable, boolean required) {
        if (dockerAvailable) {
            return;
        }
        if (required) {
            throw new IllegalStateException(
                    "[real-db] 真库测试被要求强制执行（-D" + REQUIRED_PROPERTY + "=true），"
                            + "但 Docker 守护进程不可用——CI 上真库测试被跳过 = 红（spec 033 §13.3 禁假绿①："
                            + "「全绿但其实没跑」不允许发生）");
        }
        System.out.println("[real-db] SKIP（Docker 不可用）—— 真库测试类被跳过：本地降级契约生效"
                + "（CI 设 -D" + REQUIRED_PROPERTY + "=true 使 skip 即红；跳过必须可见，不得静默——spec 033 §12#2）");
        throw new TestAbortedException(
                "[real-db] Docker 守护进程不可用：真库测试跳过（本地 skip 契约，C-4）");
    }

    /** 包内可见：单元测试替换探测器用（生产代码勿调）。 */
    static void setProbeForTesting(BooleanSupplier replacement) {
        probe = replacement;
    }
}
