package com.payment.catalog.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Catalog 领域不变量测试（可售性 + 价格最小货币单位）。
 */
class CatalogInvariantTest {

    private Sku newSku() {
        return new Sku("SKU-1001", 1L, "Annual Membership", 1999L, "CNY", "digital");
    }

    @Test
    void newSkuIsDraftAndNotSellable() {
        Sku sku = newSku();

        assertEquals(SkuStatus.DRAFT, sku.getStatus());
        assertFalse(sku.isSellable());
    }

    @Test
    void activateMakesSkuSellable() {
        Sku sku = newSku();
        sku.activate();

        assertEquals(SkuStatus.SELLABLE, sku.getStatus());
        assertTrue(sku.isSellable());
    }

    @Test
    void suspendAndDiscontinueTransitionsAreLegal() {
        // SELLABLE → SUSPENDED
        Sku suspended = newSku();
        suspended.activate();
        suspended.suspend();
        assertEquals(SkuStatus.SUSPENDED, suspended.getStatus());

        // SELLABLE → DISCONTINUED
        Sku discontinuedFromSellable = newSku();
        discontinuedFromSellable.activate();
        discontinuedFromSellable.discontinue();
        assertEquals(SkuStatus.DISCONTINUED, discontinuedFromSellable.getStatus());

        // SUSPENDED → DISCONTINUED
        Sku discontinuedFromSuspended = newSku();
        discontinuedFromSuspended.activate();
        discontinuedFromSuspended.suspend();
        discontinuedFromSuspended.discontinue();
        assertEquals(SkuStatus.DISCONTINUED, discontinuedFromSuspended.getStatus());
    }

    @Test
    void illegalTransitionsThrowStateTransitionViolation() {
        // activate 一个已经是 SELLABLE 的 SKU
        Sku sellable = newSku();
        sellable.activate();
        BizException reActivate = assertThrows(BizException.class, sellable::activate);
        assertEquals(ErrorCodes.STATE_TRANSITION_VIOLATION, reActivate.getCode());

        // suspend 一个 DRAFT 的 SKU
        Sku draft = newSku();
        BizException suspendDraft = assertThrows(BizException.class, draft::suspend);
        assertEquals(ErrorCodes.STATE_TRANSITION_VIOLATION, suspendDraft.getCode());
    }

    @Test
    void priceIsPreservedAsLongMinorUnits() {
        Sku sku = newSku();

        assertEquals(1999L, sku.getPriceMinor());
        assertEquals("CNY", sku.getCurrencyCode());

        // 领域类不得使用 float/double 表示金额
        boolean hasFloatingPoint = Arrays.stream(Sku.class.getDeclaredFields())
                .anyMatch(f -> f.getType() == float.class || f.getType() == double.class
                        || f.getType() == Float.class || f.getType() == Double.class);
        assertFalse(hasFloatingPoint, "Sku must not use float/double for price");
    }
}
