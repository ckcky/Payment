package com.payment.reconciliation.domain;

/**
 * 渠道账单来源溯源（ADR-0020）：记录本批对账到底比对了哪份账单，便于事后追溯。
 *
 * @param sourceType   来源类型：FIXTURE（本地预置）/ SFTP / API（未来演进，加载器接口不变）
 * @param locator      实际加载位置（如 fixture 文件名或远程路径）
 * @param entryCount   账单条目数
 * @param fallbackUsed 是否走了「周期 fixture 未命中 → 默认 sample.csv」的显式回退
 */
public record ChannelStatementSource(String sourceType, String locator, int entryCount, boolean fallbackUsed) {

    public static ChannelStatementSource fixture(String locator, int entryCount, boolean fallbackUsed) {
        return new ChannelStatementSource("FIXTURE", locator, entryCount, fallbackUsed);
    }
}
