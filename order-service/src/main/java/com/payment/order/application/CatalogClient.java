package com.payment.order.application;

/**
 * Catalog 服务的同步 RPC 端口（订单服务侧）。只读取 SKU 可售性与价格快照，不修改 catalog 数据。
 *
 * <p>生产实现为 Feign 客户端（{@code infra/client}）；测试用内存 fake 替代。</p>
 */
public interface CatalogClient {

    /** 获取 SKU 快照；不存在时抛 {@code NOT_FOUND} 业务异常。 */
    SkuSnapshot getSku(Long skuId);
}
