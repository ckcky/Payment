package com.payment.order.application;

/**
 * Catalog 服务的同步 RPC 端口（订单服务侧）。只读 SKU 快照，并在下单生命周期内驱动库存三段式控制：
 * 下单预占（reserve）→ 支付成功确认扣减（confirm）→ 失败/超时释放（release）。
 *
 * <p>生产实现为 Feign 客户端（{@code infra/client}）；测试用内存 fake 替代。</p>
 */
public interface CatalogClient {

    /** 获取 SKU 快照；不存在时抛 {@code NOT_FOUND} 业务异常。 */
    SkuSnapshot getSku(Long skuId);

    /** 下单预占库存（幂等键 reservationId）。库存不足抛 {@code CONFLICT}。 */
    void reserveStock(ReserveStockCommand request);

    /** 支付成功确认扣减（幂等键 deductId）。 */
    void confirmStock(ConfirmStockCommand request);

    /** 支付失败/超时释放预占（幂等）。 */
    void releaseStock(ReleaseStockCommand request);

    /** 秒杀配额预扣（Lua 原子，catalog 侧）：非秒杀品直接放行；配额不足/Redis 不可用返回 allowed=false。 */
    SeckillResult trySeckillDeduct(Long skuId, long quantity);

    /** 秒杀订单失败/超时回补配额（幂等）。 */
    void rollbackSeckill(Long skuId, long quantity);
}
