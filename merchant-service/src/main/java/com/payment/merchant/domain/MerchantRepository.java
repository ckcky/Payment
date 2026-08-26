package com.payment.merchant.domain;

import java.util.Optional;

/**
 * Persistence port for {@link Merchant}. Implemented in the infra layer (dependency inversion);
 * the domain layer depends only on this interface.
 */
public interface MerchantRepository {

    Optional<Merchant> findById(Long id);

    Optional<Merchant> findByCode(String merchantCode);

    Merchant save(Merchant merchant);
}
