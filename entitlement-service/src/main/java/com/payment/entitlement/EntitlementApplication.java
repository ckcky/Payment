package com.payment.entitlement;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.payment.entitlement.infra.persistence")
public class EntitlementApplication {

    public static void main(String[] args) {
        SpringApplication.run(EntitlementApplication.class, args);
    }
}
