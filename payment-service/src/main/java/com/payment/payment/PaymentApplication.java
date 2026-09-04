package com.payment.payment;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * payment-service（Feature 015 / P3 合并）：支付域 + 退款域单体部署。
 * 扫描范围同时覆盖 com.payment.payment 与 com.payment.refund 两个领域包。
 */
@SpringBootApplication(scanBasePackages = {"com.payment.payment", "com.payment.refund"})
@EnableFeignClients(basePackages = {"com.payment.payment", "com.payment.refund"})
@EnableScheduling
@MapperScan({"com.payment.payment.infra.persistence", "com.payment.refund.infra.persistence"})
public class PaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}
