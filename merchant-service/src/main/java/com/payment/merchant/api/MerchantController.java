package com.payment.merchant.api;

import com.payment.merchant.api.dto.MerchantResponse;
import com.payment.merchant.api.dto.RegisterMerchantRequest;
import com.payment.merchant.application.MerchantApplicationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/merchants")
public class MerchantController {

    private final MerchantApplicationService merchantApplicationService;

    public MerchantController(MerchantApplicationService merchantApplicationService) {
        this.merchantApplicationService = merchantApplicationService;
    }

    @PostMapping
    public MerchantResponse register(@RequestBody RegisterMerchantRequest request) {
        return MerchantResponse.from(merchantApplicationService.register(
                request.code(), request.name(), request.settlementAccountRef()));
    }

    @PostMapping("/{id}/approve")
    public MerchantResponse approve(@PathVariable Long id) {
        return MerchantResponse.from(merchantApplicationService.approve(id));
    }

    @PostMapping("/{id}/suspend")
    public MerchantResponse suspend(@PathVariable Long id) {
        return MerchantResponse.from(merchantApplicationService.suspend(id));
    }

    @PostMapping("/{id}/terminate")
    public MerchantResponse terminate(@PathVariable Long id) {
        return MerchantResponse.from(merchantApplicationService.terminate(id));
    }

    @GetMapping("/{id}")
    public MerchantResponse get(@PathVariable Long id) {
        return MerchantResponse.from(merchantApplicationService.get(id));
    }
}
