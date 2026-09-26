package com.payment.channelgateway.domain;

import java.util.List;
import java.util.Optional;

/**
 * 渠道单仓储边界（领域接口，spec 041）。
 *
 * <p>渠道单就是渠道层的订单：一次渠道交互一个 {@code channelNo}（{@code CH}+雪花）。
 * 本接口是它的<b>增删改查</b>边界，实现落在 {@code channelgateway.infra.persistence}，
 * 由 {@code ChannelOrderService} 调用——Controller 不直达仓储。</p>
 *
 * <p>ADR-0013 修订后重试在请求内联完成，<b>仓储不再承担重试调度</b>：没有「待重试队列」查询，
 * 也没有 {@code next_retry_at} 落库字段。</p>
 */
public interface ChannelOrderRepository {

    Optional<ChannelOrder> findById(Long id);

    /**
     * 按渠道单业务号查询（{@code uk_channel_orders_channel_no} 唯一，故至多一条）。
     *
     * <p>这是渠道域自己的主键语义——对外交互一律用 {@code channelNo}，不用自增 {@code id}（ADR-0063）。</p>
     */
    Optional<ChannelOrder> findByChannelNo(String channelNo);

    List<ChannelOrder> findByPaymentNo(String paymentNo);

    ChannelOrder save(ChannelOrder order);
}
