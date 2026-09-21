package com.payment.settlement;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableFeignClients
/** 台账补投调度器（com.payment.settlement.posting）需要调度基建（spec 034 §9.1）。 */
@EnableScheduling
@MapperScan({"com.payment.settlement.infra.persistence",
        "com.payment.settlement.posting.infra.persistence"})
public class SettlementApplication {

    public static void main(String[] args) {
        SpringApplication.run(SettlementApplication.class, args);
    }
}
