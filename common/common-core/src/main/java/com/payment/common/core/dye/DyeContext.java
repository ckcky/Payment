package com.payment.common.core.dye;

/**
 * 染色上下文（spec 030 / ADR-0076，FR-161）：ThreadLocal 承载单次请求的模态。
 *
 * <p>三段式透传（仿 traceId）：入站 {@code DyeFilter} 写 → 本类承载 → 出站
 * {@code DyeRequestInterceptor} 读并写下游客。</p>
 *
 * <h3>使用纪律</h3>
 * <ul>
 *   <li>{@code null}（未染色）等价于 {@link DyeMode#MOCK}——**安全默认**；</li>
 *   <li><b>反向路径</b>（退款 / 主动查询 / 超时扫描）没有入站请求，ThreadLocal 为空，
 *       MUST 从 {@code payment_attempts.extra_json} 的 {@code channelMode} 落库值还原，
 *       并用 {@link #runWith} 包裹渠道调用；</li>
 *   <li>用完 MUST {@link #clear()}（尤其线程池复用场景）。</li>
 * </ul>
 */
public final class DyeContext {

    private static final ThreadLocal<DyeMode> CURRENT = new ThreadLocal<>();

    private DyeContext() {
    }

    /** 当前模态；未染色返回 {@code null}（调用方应按 MOCK 处理）。 */
    public static DyeMode current() {
        return CURRENT.get();
    }

    /** 是否沙箱（真实渠道）模态；未染色 ⇒ {@code false}。 */
    public static boolean isSandbox() {
        return CURRENT.get() == DyeMode.SANDBOX;
    }

    /** 设置当前模态（{@code null} 表示清除）。 */
    public static void set(DyeMode mode) {
        if (mode == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(mode);
        }
    }

    /** 清除当前模态（ MUST 在 finally 中调用）。 */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 以指定模态执行动作，结束后还原调用前的模态（供<b>反向路径</b>包裹渠道调用）。
     *
     * <p>用「还原」而非「直接 clear」是因为调用方可能已在某个模态上下文中
     * （例如退款请求本身带染色头），粗暴 clear 会破坏外层语义。</p>
     */
    public static void runWith(DyeMode mode, Runnable action) {
        DyeMode previous = CURRENT.get();
        try {
            set(mode);
            action.run();
        } finally {
            set(previous);
        }
    }

    /**
     * 以指定模态执行有返回值动作（同上，供反向路径取渠道结果）。
     *
     * @param <T> 返回值类型
     */
    public static <T> T callWith(DyeMode mode, java.util.function.Supplier<T> action) {
        DyeMode previous = CURRENT.get();
        try {
            set(mode);
            return action.get();
        } finally {
            set(previous);
        }
    }
}
