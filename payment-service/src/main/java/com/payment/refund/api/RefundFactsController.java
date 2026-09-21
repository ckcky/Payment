package com.payment.refund.api;

import com.payment.refund.api.dto.RefundFactResponse;
import com.payment.refund.application.RefundFactsService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对账事实查询接口：向 reconciliation-service 暴露平台侧已确认的退款事实。
 *
 * <p>spec 032 / H-032-1：支持可选 {@code period} 查询参数（YYYY-MM-DD，按创建日期过滤；
 * 缺省全量，保留兼容窗口）。</p>
 */
@RestController
public class RefundFactsController {

    private final RefundFactsService factsService;

    public RefundFactsController(RefundFactsService factsService) {
        this.factsService = factsService;
    }

    @GetMapping("/internal/refunds/confirmed-facts")
    public List<RefundFactResponse> confirmedFacts(
            @RequestParam(required = false) String period) {
        return factsService.confirmedFacts(period);
    }
}
