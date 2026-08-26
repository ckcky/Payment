package com.payment.entitlement.api;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.entitlement.domain.EntitlementRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 权益查询接口。
 */
@RestController
@RequestMapping("/entitlements")
public class EntitlementController {

    private final EntitlementRepository repository;

    public EntitlementController(EntitlementRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{id}")
    public EntitlementResponse getEntitlement(@PathVariable Long id) {
        return repository.findById(id)
                .map(EntitlementResponse::from)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "entitlement not found: " + id));
    }
}
