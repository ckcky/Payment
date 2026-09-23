package com.payment.payment.application.channel.spi;

/**
 * 渠道插件<b>工厂</b>（渠道插件化内核 / SPI-05）。
 *
 * <h3>为什么要有工厂，而不是让插件自己 {@code @Component}</h3>
 * 插件不只是「一个实现」，而是「一份实现 + 一堆渠道私有的装配物」：SDK client、密钥、
 * 超时、连接池。让插件类自己打 {@code @Component} 会把这些装配细节塞进插件本体，
 * 于是插件既描述业务能力又负责依赖装配——接第二家渠道时这些装配代码无法复用，
 * 且单测插件必须拖上整个 Spring 上下文。工厂把「<b>造</b>」和「<b>用</b>」分开。</p>
 *
 * <h3>{@link #descriptor()} 不创建实例即可读到（关键设计）</h3>
 * 描述符是<b>静态</b>能力声明，读它不该付出「创建 SDK client、连一次网络」的代价。
 * 启动期的两件事都只需要元数据：① 校验配置里的渠道码是否都已注册；② 路由预览端点。
 * 若描述符必须靠 {@code create()} 才能拿到，启动期就被迫实例化全部渠道插件——
 * 「没启用 Stripe 却要连 Stripe」正是要避免的。</p>
 *
 * <h3>SPI 本体：{@code ServiceLoader}</h3>
 * 本接口是标准 Java SPI：外部渠道 jar 在
 * {@code META-INF/services/com.payment.payment.application.channel.spi.ChannelPluginFactory}
 * 里声明实现，即可<b>不重新编译 payment-service</b> 完成接入。
 * 两路来源由 {@code infra.channel.ChannelPluginFactoryLocator} 合并：
 * <ul>
 *   <li><b>Spring 容器内的工厂 Bean</b>——主流方式，享受配置注入与条件装配；</li>
 *   <li><b>{@code ServiceLoader} 发现的工厂</b>——真正的插件化通道，要求无参构造。</li>
 * </ul>
 * 同一 {@code code} 出现两个工厂视为结构性错误（启动期失败），
 * 否则会出现「路由选了 A、实际调用 B」的幽灵缺陷。</p>
 */
public interface ChannelPluginFactory {

    /**
     * 本工厂产出的插件描述符（<b>不创建实例</b>即可获得）。
     *
     * @return 描述符，{@code code} 非空且在全局唯一
     */
    ChannelPluginDescriptor descriptor();

    /**
     * 创建插件实例。
     *
     * <p>Spring 侧的工厂由容器调用，可安全依赖注入；{@code ServiceLoader} 侧的工厂
     * 由内核直接调用，MUST 提供无参构造且自行处理缺省配置。</p>
     *
     * @return 已装配的插件（同一工厂可被要求创建多次 —— 幂等性由实现保证，
     *         但注册表只会保留一次）
     */
    ChannelPlugin create();
}
