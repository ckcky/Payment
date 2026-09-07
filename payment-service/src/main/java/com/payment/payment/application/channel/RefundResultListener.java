package com.payment.payment.application.channel;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * 渠道退款结果监听端口（spec 019 / D7）：异步受理模式下，渠道在延迟后把权威退款结果
 * 「推送」给平台——真实渠道走 HTTP 回调（{@code POST /internal/refunds/{refundNo}/channel-callback}），
 * 进程内 Mock 渠道直接经本端口驱动同一收敛编排（RefundResultProcessor），语义等价。
 */
public interface RefundResultListener {

    /** 渠道推送一次退款结果（按业务单号 PMRF 寻址；重复推送由退款状态机终态吸收）。 */
    void onChannelRefundResult(String refundNo, ChannelResult result);
}
