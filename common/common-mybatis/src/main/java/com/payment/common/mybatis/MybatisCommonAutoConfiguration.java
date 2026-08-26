package com.payment.common.mybatis;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * common-mybatis 自动配置：把审计填充与拦截器（乐观锁、分页）注册到各服务。
 */
@AutoConfiguration
public class MybatisCommonAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MetaObjectHandler auditMetaObjectHandler() {
        return new AuditMetaObjectHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 乐观锁：并发状态迁移防覆盖（资金正确性）
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        // 分页：对账/结算/退款查询用
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
