package com.payment.merchant.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.merchant.domain.Merchant;
import com.payment.merchant.domain.MerchantRepository;
import org.springframework.stereotype.Service;

/**
 * Application service orchestrating merchant use cases. State transitions are delegated
 * to the {@link Merchant} state machine; this layer only loads, invokes, persists, returns.
 */
@Service
public class MerchantApplicationService {

    private final MerchantRepository merchantRepository;

    public MerchantApplicationService(MerchantRepository merchantRepository) {
        this.merchantRepository = merchantRepository;
    }

    public Merchant register(String code, String name, String settlementAccountRef) {
        if (merchantRepository.findByCode(code).isPresent()) {
            throw BizException.of(ErrorCodes.CONFLICT, "Merchant code already exists: " + code);
        }
        Merchant merchant = new Merchant(code, name, settlementAccountRef);
        return merchantRepository.save(merchant);
    }

    public Merchant approve(Long id) {
        Merchant merchant = get(id);
        merchant.approve();
        return merchantRepository.save(merchant);
    }

    public Merchant suspend(Long id) {
        Merchant merchant = get(id);
        merchant.suspend();
        return merchantRepository.save(merchant);
    }

    public Merchant terminate(Long id) {
        Merchant merchant = get(id);
        merchant.terminate();
        return merchantRepository.save(merchant);
    }

    public Merchant get(Long id) {
        return merchantRepository.findById(id)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "Merchant not found: " + id));
    }
}
