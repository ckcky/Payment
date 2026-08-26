package com.payment.fulfillment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
public class FulfillmentApplication {

    public static void main(String[] args) {
        SpringApplication.run(FulfillmentApplication.class, args);
    }
}
