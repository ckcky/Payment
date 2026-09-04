package com.payment.entitlement.api;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.entitlement.domain.EntitlementRepository;
import java.util.List;
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

    /**
     * 按订单查询权益（只读）。
     *
     * <p>与 {@link #getEntitlement} 的差异：一个订单可对应多条权益（多 SKU、多次授予），
     * 故返回<b>列表</b>而非单条；订单无任何权益时返回空列表（而非 404）——
     * 「还没授予」是合法中间态，不是错误。校验「权益已发放」应断言列表非空且状态为 AVAILABLE
     * （spec 011 / FR-008）。</p>
     *
     * @param orderNo 订单号（业务键，非主键）
     * @return 该订单授予的全部权益，按仓储返回顺序；无则空列表
     */
    @GetMapping("/by-order/{orderNo}")
    public List<EntitlementResponse> listEntitlementsByOrderId(@PathVariable String orderNo) {
        return repository.findByOrderNo(orderNo).stream()
                .map(EntitlementResponse::from)
                .toList();
    }
}
