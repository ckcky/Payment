package com.payment.payment.application.reliability;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * UNKNOWN 主动查询调度器（spec US2 / ADR-0003）：按固定间隔驱动 {@link ChannelQueryService#queryRound()}。
 *
 * <p>间隔由 {@code payment.reliability.query-interval-ms} 配置（默认 15s）。当前单节点部署，
 * 调度单实例运行（spec Assumptions）。</p>
 */
@Component
public class ChannelQueryScheduler {

    private final ChannelQueryService queryService;

    public ChannelQueryScheduler(ChannelQueryService queryService) {
        this.queryService = queryService;
    }

    @Scheduled(fixedDelayString = "${payment.reliability.query-interval-ms:15000}")
    public void run() {
        queryService.queryRound();
    }
}
