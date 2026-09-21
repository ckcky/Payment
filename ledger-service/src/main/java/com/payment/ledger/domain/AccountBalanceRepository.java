package com.payment.ledger.domain;

import java.util.List;
import java.util.Optional;

/**
 * 余额投影仓储边界（spec 031 §10）：投影写入由 {@code LedgerRepository#save} 在
 * **同一本地事务**内完成（构造性防漂移）；本端口承担读侧查询与全量重建（低频管理路径）。
 */
public interface AccountBalanceRepository {

    Optional<AccountBalance> find(long accountInstanceId, String currency);

    List<AccountBalance> findAll();

    /** 全量重建：清空投影后按 {@code ledger_entries} 重算（离线/低频，非在线路径）。 */
    int rebuild();
}
