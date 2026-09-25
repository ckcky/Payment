package com.payment.common.dto.channel;

import java.util.Objects;

/**
 * 商品信息（平台 → 渠道，spec 030 / FR-102）。
 *
 * <p>支付宝 {@code subject} 为必填、微信 {@code description} 为必填——这是「极简契约装不下
 * 真实渠道」的具体体现之一，本类型即为此而设。</p>
 *
 * @param title       商品标题（渠道侧多称 subject / body），<b>必填</b>
 * @param description 商品描述（可空；部分渠道允许省略）
 */
public record Goods(String title, String description) {

    public Goods {
        Objects.requireNonNull(title, "goods.title");
    }

    /** 只有标题、无描述的简写工厂（多数 mock / 沙箱场景够用）。 */
    public static Goods of(String title) {
        return new Goods(title, null);
    }
}
