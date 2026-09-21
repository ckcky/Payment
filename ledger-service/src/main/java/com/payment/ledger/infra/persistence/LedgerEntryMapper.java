package com.payment.ledger.infra.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.payment.ledger.domain.TrialBalanceRow;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * ledger_entries 表 Mapper（MyBatis-Plus BaseMapper）。
 */
public interface LedgerEntryMapper extends BaseMapper<LedgerEntryEntity> {

    /**
     * 试算平衡聚合（§10 ②，分录 = Source of Truth）：按「账户实例 × 币种」汇总借贷发生额。
     *
     * <p>LEFT JOIN postings 仅为按期间过滤；分录本身是聚合对象，游离/未关联 posting 的
     * 分录（数据损坏/手工篡改，正是 FR-007 要检出的失衡来源）在全期间口径下 MUST 计入，
     * 否则失衡被静默吞掉。</p>
     *
     * @param period {@code YYYY-MM}；null = 全期间（含未关联 posting 的游离分录）
     */
    @Select("SELECT e.account_id AS accountId, e.currency AS currency, "
            + "SUM(CASE WHEN e.direction = 'DEBIT' THEN e.amount_minor ELSE 0 END) AS debitTotal, "
            + "SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount_minor ELSE 0 END) AS creditTotal "
            + "FROM ledger_entries e LEFT JOIN postings p ON p.id = e.posting_id "
            + "WHERE (#{period,jdbcType=VARCHAR} IS NULL OR p.period = #{period,jdbcType=VARCHAR}) "
            + "GROUP BY e.account_id, e.currency ORDER BY e.account_id, e.currency")
    List<TrialBalanceRow> trialBalance(@Param("period") String period);
}
