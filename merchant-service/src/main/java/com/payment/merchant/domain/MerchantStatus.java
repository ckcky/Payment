package com.payment.merchant.domain;

/**
 * Merchant lifecycle state machine.
 *
 * <p>Transition matrix (state changes happen ONLY via {@link Merchant} methods):
 * <ul>
 *   <li>PENDING_REVIEW -> ACTIVE (approve)</li>
 *   <li>SUSPENDED -> ACTIVE (approve)</li>
 *   <li>ACTIVE -> SUSPENDED (suspend)</li>
 *   <li>any (except TERMINATED) -> TERMINATED (terminate, terminal)</li>
 * </ul>
 */
public enum MerchantStatus {
    PENDING_REVIEW,
    ACTIVE,
    SUSPENDED,
    TERMINATED
}
