package com.payment.refund.application;

import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.application.channel.RefundResultListener;
import org.springframework.stereotype.Component;

/**
 * 渠道退款结果推送桥（spec 019 / D7）：把渠道侧（进程内 Mock / 未来真实渠道驱动）
 * 的结果推送接到退款收敛编排 {@link RefundRpcCallbackService#handleChannelCallback}——
 * 与 HTTP 回调端点（{@code POST /internal/refunds/{refundNo}/channel-callback}）
 * 走同一收敛路径，不留双路径。
 */
@Component
public class MockRefundResultBridge implements RefundResultListener {

    private final RefundRpcCallbackService callbackService;

    public MockRefundResultBridge(RefundRpcCallbackService callbackService) {
        this.callbackService = callbackService;
    }

    @Override
    public void onChannelRefundResult(String refundNo, ChannelResult result) {
        callbackService.handleChannelCallback(refundNo, result);
    }
}
