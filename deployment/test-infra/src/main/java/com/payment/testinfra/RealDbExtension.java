package com.payment.testinfra;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.core.annotation.AnnotatedElementUtils;

/**
 * {@link RealDb} 的执行扩展：在 Spring context 创建之前（JUnit beforeAll 阶段）
 * 完成容器就绪、类内独占建库、schema 装配与数据源属性注入。
 *
 * <p><b>为什么用 System properties 而不是 {@code @DynamicPropertySource}</b>：后者必须
 * 写在被测测试类的静态方法上，无法收进共享基座；System properties 是 Spring Boot
 * 属性源的最高优先级，且在任何 context 创建前生效，等效且对服务零侵入。</p>
 */
public class RealDbExtension implements BeforeAllCallback {

    /** Boot 数据源属性（覆盖服务 application.yml 的 H2 配置）。 */
    static final String DATASOURCE_URL = "spring.datasource.url";
    static final String DATASOURCE_USERNAME = "spring.datasource.username";
    static final String DATASOURCE_PASSWORD = "spring.datasource.password";

    @Override
    public void beforeAll(ExtensionContext context) {
        Class<?> testClass = context.getRequiredTestClass();
        RealDb config = AnnotatedElementUtils.findMergedAnnotation(testClass, RealDb.class);
        if (config == null) {
            return; // 直接挂在类上的扩展（非经 @RealDb）：不接管
        }
        DockerContract.ensureAvailableOrSkip(testClass);
        List<SchemaBootstrap.Script> scripts = new ArrayList<>();
        for (String name : config.schemaScripts()) {
            scripts.add(new SchemaBootstrap.Script(name));
        }
        String database = RealMysqlTestSupport.RealMysqlRuntime.ensureClassDatabase(testClass, scripts);
        System.setProperty(DATASOURCE_URL, RealMysqlContainer.jdbcUrl(database, false));
        System.setProperty(DATASOURCE_USERNAME, RealMysqlContainer.username());
        System.setProperty(DATASOURCE_PASSWORD, RealMysqlContainer.password());
    }
}
