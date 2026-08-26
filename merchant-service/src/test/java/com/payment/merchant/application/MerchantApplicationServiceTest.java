package com.payment.merchant.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.merchant.domain.Merchant;
import com.payment.merchant.domain.MerchantStatus;
import com.payment.merchant.infra.InMemoryMerchantRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MerchantApplicationServiceTest {

    private final InMemoryMerchantRepository repository = new InMemoryMerchantRepository();
    private final MerchantApplicationService service = new MerchantApplicationService(repository);

    @Test
    void registerAndApproveReturnsEligibleMerchant() {
        Merchant registered = service.register("M-001", "Acme", "settlement-1");
        assertThat(registered.getStatus()).isEqualTo(MerchantStatus.PENDING_REVIEW);
        assertThat(registered.isEligibleForSettlement()).isFalse();

        Merchant approved = service.approve(registered.getId());

        assertThat(approved.getStatus()).isEqualTo(MerchantStatus.ACTIVE);
        assertThat(approved.isEligibleForSettlement()).isTrue();
    }

    @Test
    void duplicateRegisterThrows() {
        service.register("M-001", "Acme", "settlement-1");

        assertThatThrownBy(() -> service.register("M-001", "Other", "settlement-2"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.CONFLICT));
    }
}
