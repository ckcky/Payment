package com.payment.common.core;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 模块边界（T016）。
 *
 * <p>微服务形态下，服务之间的隔离由 Maven 模块图在构建期强制（没有哪个服务的 pom 依赖另一个
 * 服务模块，只能依赖 common-*），这是编译期保证，不是运行期包访问校验。本测试在 common-core
 * 内验证两条共享层不变量：</p>
 * <ol>
 *   <li>共享模块不得反向依赖任何业务服务包（common ≠ 业务，杜绝共享代码里写服务逻辑）。</li>
 *   <li>核心值对象（money/idempotency/result）保持框架无关，不得依赖 Spring。</li>
 * </ol>
 */
class ModuleBoundaryTest {

    private final JavaClasses commonCore = new ClassFileImporter()
            .importPackages("com.payment.common.core");

    @Test
    void commonModulesMustNotDependOnServicePackages() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.payment.common.core..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.payment.merchant..", "com.payment.catalog..", "com.payment.order..",
                        "com.payment.payment..", "com.payment.refund..", "com.payment.fulfillment..",
                        "com.payment.entitlement..", "com.payment.reconciliation..", "com.payment.settlement..");
        rule.check(commonCore);
    }

    @Test
    void coreValueObjectsMustNotDependOnSpring() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(
                        "com.payment.common.core.money..",
                        "com.payment.common.core.idempotency..",
                        "com.payment.common.core.result..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework..");
        rule.check(commonCore);
    }
}
