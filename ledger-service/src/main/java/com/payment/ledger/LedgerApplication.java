package com.payment.ledger;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 账本服务启动类：复式记账的账务事实底座（Constitution §II.3）。
 *
 * <p>本服务**只被依赖、不反向依赖**任何业务领域（FR-005 / Constitution §III）：
 * 记账请求由 payment / refund / settlement 经同步 RPC 发起。</p>
 */
@SpringBootApplication
@EnableFeignClients
@MapperScan("com.payment.ledger.infra.persistence")
public class LedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerApplication.class, args);
    }
}
