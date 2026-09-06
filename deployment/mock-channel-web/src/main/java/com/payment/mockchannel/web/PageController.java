package com.payment.mockchannel.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 页面入口：把带查询参数的语义路径转发到同源静态页。
 *
 * <p>静态资源本身忽略查询串，页面 JS 从 {@code location.search} 读取
 * paymentNo / orderNo / amountMinor / currencyCode 自渲染（业务单号，ADR-0063）。</p>
 */
@Controller
public class PageController {

    /** 收银台页：{@code GET /cashier?paymentNo=&orderNo=&amountMinor=&currencyCode=}。 */
    @GetMapping("/cashier")
    public String cashier() {
        return "forward:/cashier.html";
    }

    /** 演示控制台页。 */
    @GetMapping("/demo")
    public String demo() {
        return "forward:/demo.html";
    }
}
