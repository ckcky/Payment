package com.payment.ledger.domain.posting;

import com.payment.common.dto.rpc.AccountingEventType;
import com.payment.ledger.domain.Posting;

import java.util.Optional;

/**
 * 原交易回查端口（ADJUSTMENT 红冲/红蓝字用）：规则据 {@code (eventType, sourceId)}
 * 读回被冲销的原账本交易，由**账本自身**取反生成分录——上游 MUST NOT 传方向/科目
 * （§7.6 实现期修正：契约只给 `reversesEventType/reversesSourceId` 引用）。
 */
@FunctionalInterface
public interface OriginalPostingLookup {

    Optional<Posting> findByEvent(AccountingEventType eventType, String sourceId);
}
