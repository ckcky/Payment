package com.payment.payment.infra.channel;

import com.payment.payment.application.channel.spi.ChannelPlugin;
import com.payment.payment.application.channel.spi.ChannelPluginFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * 渠道插件工厂的<b>定位器</b>（渠道插件化内核 / SPI-07）：把「工厂 + SPI」两路来源合并成
 * 一份「渠道码 → 工厂」的确定性表，并负责创建插件实例。
 *
 * <h3>两路来源</h3>
 * <ol>
 *   <li><b>Spring 容器内的工厂 Bean</b>（主流）：享受配置注入、条件装配、生命周期管理。</li>
 *   <li><b>{@link ServiceLoader} 发现的工厂</b>（真正的 SPI 通道）：外部渠道 jar 在
 *       {@code META-INF/services/com.payment.payment.application.channel.spi.ChannelPluginFactory}
 *       声明实现，即可<b>不重新编译 payment-service</b> 完成接入——这才是「插件化」的本体，
 *       Spring Bean 发现只是它在本进程内的便捷形式。</li>
 * </ol>
 *
 * <h3>为什么要合并而不是二选一</h3>
 * 只用 Spring：外部 jar 想接入必须改本工程的配置类，插件化名存实亡。
 * 只用 ServiceLoader：插件拿不到 Spring 的配置注入，每个插件都要自己读环境变量——
 * 那正是要把渠道差异关进笼子时最不该出现的重复代码。
 * 合并后<b>Spring 优先</b>（同码冲突时容器内的胜出），既保住注入能力又不封死外部扩展。</p>
 *
 * <h3>同码双工厂 = 结构性错误</h3>
 * 两个工厂声明同一个渠道码，注册若静默覆盖会变成「路由选了 A、实际调用 B」的幽灵缺陷。
 * 故在<b>启动期</b>直接失败并点名两个工厂类（ADR-0049 第 2 条精神）。</p>
 */
@Component
public class ChannelPluginFactoryLocator {

    private static final Logger log = LoggerFactory.getLogger(ChannelPluginFactoryLocator.class);

    private final Map<String, ChannelPluginFactory> byCode;

    public ChannelPluginFactoryLocator(List<ChannelPluginFactory> springFactories) {
        Map<String, ChannelPluginFactory> map = new LinkedHashMap<>();
        List<ChannelPluginFactory> spring = springFactories == null ? List.of() : springFactories;
        for (ChannelPluginFactory factory : spring) {
            register(map, factory, true);
        }
        // ServiceLoader 通道：外部 jar 插件。容器里已有的同码工厂优先，故这里冲突时让位。
        for (ChannelPluginFactory factory : ServiceLoader.load(ChannelPluginFactory.class)) {
            register(map, factory, false);
        }
        this.byCode = Map.copyOf(map);
        log.info("channel plugin factories located: {}", byCode.keySet());
    }

    /**
     * 注册单个工厂。
     *
     * @param overrideExisting 是否允许覆盖已存在的同码工厂（Spring 侧为 true 意味着
     *                         容器内的重复声明应当报错；ServiceLoader 侧为 false 意味着让位）
     */
    private static void register(Map<String, ChannelPluginFactory> map,
                                 ChannelPluginFactory factory, boolean failOnDuplicate) {
        String code = factory.descriptor().code();
        if (code == null || code.isBlank()) {
            throw new IllegalStateException("channel plugin factory " + factory.getClass().getName()
                    + " declared a blank channel code; descriptor().code() must be non-blank");
        }
        String key = code.trim().toUpperCase();
        ChannelPluginFactory previous = map.get(key);
        if (previous != null) {
            if (failOnDuplicate) {
                throw new IllegalStateException("duplicate channel plugin factory for code '" + key
                        + "': " + previous.getClass().getName() + " and " + factory.getClass().getName()
                        + "; channel codes must be globally unique");
            }
            log.debug("skipping service-loaded factory {} for code '{}': a spring-managed factory exists",
                    factory.getClass().getName(), key);
            return;
        }
        map.put(key, factory);
    }

    /** 已定位的渠道码（大写，字典序）。 */
    public List<String> codes() {
        return new ArrayList<>(byCode.keySet());
    }

    /**
     * 创建全部插件实例。
     *
     * <p>创建失败<b>逐个隔离</b>：一个渠道插件装配失败不该拖垮整个注册表
     * （否则「Stripe 密钥忘了配」会让连 mock 都不可用）。
     * 隔离之后仍需<b>重新抛出</b>——静默吞掉会变成「渠道悄悄消失」，
     * 而路由会在运行时才发现无候选，排障成本远高于启动期报错。</p>
     */
    public List<ChannelPlugin> createAll() {
        List<ChannelPlugin> plugins = new ArrayList<>();
        for (Map.Entry<String, ChannelPluginFactory> entry : byCode.entrySet()) {
            plugins.add(entry.getValue().create());
        }
        return List.copyOf(plugins);
    }
}
