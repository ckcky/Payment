package com.payment.refund;

import com.payment.payment.PaymentApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Feature 015 / P3：refund 包并入 payment-service 后，测试上下文统一指向
 * {@link PaymentApplication}（com.payment.refund 包内不再保留独立启动类）。
 */
@SpringBootTest(classes = PaymentApplication.class)
class RefundApplicationTests {

    @Test
    void contextLoads() {
    }
}
