package com.payment.common.core.id;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 业务单号门面：{@code 两字母前缀 + 雪花 ID}（ADR-0062）。
 *
 * <p>每个服务进程持有一个全局单例生成器；workerId 优先取环境变量
 * {@code PAYMENT_WORKER_ID}（0~31），否则用 {@code server.port % 32} 派生。
 * 服务注入端口即可保证同机多进程 workerId 错开。</p>
 *
 * <p>用法：{@code BusinessNos.of(BusinessNoType.ORDER, 8083)} → "OR123456789012345678"。
 * 各服务在 Spring 配置里注册一个 {@code Supplier<String>}（或直接在生成处传入本服务端口）。</p>
 */
public final class BusinessNos {

    private static final AtomicReference<SnowflakeIdWorker> SHARED = new AtomicReference<>();

    private BusinessNos() {
    }

    /** 初始化进程级单例（幂等，先到先得）。 */
    public static SnowflakeIdWorker init(int workerIdSource) {
        String env = System.getenv("PAYMENT_WORKER_ID");
        SnowflakeIdWorker worker = (env != null && env.matches("\\d+"))
                ? new SnowflakeIdWorker(Long.parseLong(env) % 32, 1)
                : SnowflakeIdWorker.forPort((int) (workerIdSource % 32));
        SHARED.compareAndSet(null, worker);
        return SHARED.get();
    }

    private static SnowflakeIdWorker shared() {
        SnowflakeIdWorker w = SHARED.get();
        if (w == null) {
            // 未显式初始化时兜底：用本机地址 hash 派生，保证可用性
            int seed;
            try {
                seed = InetAddress.getLocalHost().getHostName().hashCode();
            } catch (Exception e) {
                seed = (int) ProcessHandle.current().pid();
            }
            init(Math.abs(seed));
            w = SHARED.get();
        }
        return w;
    }

    /** 生成带前缀的业务单号。 */
    public static String of(BusinessNoType type) {
        return type.prefix() + shared().nextId();
    }

    /** 校验单号格式（前缀匹配 + 纯数字后缀）。 */
    public static boolean isValid(String no, BusinessNoType type) {
        if (!type.matches(no) || no.length() < 5 || no.length() > 32) {
            return false;
        }
        for (int i = 2; i < no.length(); i++) {
            if (!Character.isDigit(no.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
