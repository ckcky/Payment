package com.payment.testinfra;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 真库 Spring 集成测试组合注解（spec 033 §5.2 O-1「RealDb @SpringBootTest」）。
 *
 * <p>元注解 {@link SpringBootTest} + {@link RealDbExtension} + {@code @Tag("real-db")} 三合一：
 * 服务侧写 {@code @RealDb(schemaScripts = "03-payment-schema.sql")} 即获得——</p>
 * <ul>
 *   <li>常规 Spring Boot 上下文（发现服务的 {@code @SpringBootConfiguration}）；</li>
 *   <li>beforeAll 阶段：容器就绪 → 建类内独占库 → 灌 {@code deployment/schema/} 脚本 →
 *       把 {@code spring.datasource.url/username/password} 写入 System properties
 *       （Spring Boot 属性源最高优先级，context 创建前生效——服务 application.yml 里的
 *       H2 配置被真库覆盖，无需服务改任何配置）；</li>
 *   <li>JUnit tag {@code real-db}（CI real-db job 的选择器；与 L2a 的 H2 用例并存不互斥，
 *       spec 033 §5.4「并存，不搬走」）。</li>
 * </ul>
 *
 * <p>本注解与 {@link RealMysqlTestSupport} 共享同一容器与幂等建库逻辑
 * （{@link RealMysqlTestSupport.RealMysqlRuntime}）：两类机制同时挂在同一测试类上也只装配一次。</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag("real-db")
@ExtendWith(RealDbExtension.class)
@SpringBootTest
public @interface RealDb {

    /** 需要灌入本类独占库的 {@code deployment/schema/} 脚本名（按声明顺序执行）。 */
    String[] schemaScripts() default {};
}
