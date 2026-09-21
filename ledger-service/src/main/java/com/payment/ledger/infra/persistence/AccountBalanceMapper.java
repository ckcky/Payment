package com.payment.ledger.infra.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 余额投影 Mapper（spec 031 §10）：
 *
 * <p>累加采用「先 UPDATE 原子累加、0 行再 INSERT、撞键再 UPDATE」三步（H2/MySQL 双活，
 * 刻意不用 {@code ON DUPLICATE KEY UPDATE}——MariaDB/H2 方言差异）。同事务内同账户多行
 * 先聚合再单条累加，避免间隙死锁。</p>
 */
@Mapper
public interface AccountBalanceMapper {

    @Select("SELECT account_instance_id, currency, debit_total, credit_total, entry_count, "
            + "last_entry_id, updated_at FROM account_balances "
            + "WHERE account_instance_id = #{accountId} AND currency = #{currency}")
    AccountBalanceEntity findByKey(@Param("accountId") long accountId, @Param("currency") String currency);

    @Select("SELECT account_instance_id, currency, debit_total, credit_total, entry_count, "
            + "last_entry_id, updated_at FROM account_balances ORDER BY account_instance_id, currency")
    List<AccountBalanceEntity> findAll();

    @Update("UPDATE account_balances SET debit_total = debit_total + #{debitDelta}, "
            + "credit_total = credit_total + #{creditDelta}, entry_count = entry_count + #{entryCount}, "
            + "last_entry_id = #{lastEntryId}, updated_at = #{now} "
            + "WHERE account_instance_id = #{accountId} AND currency = #{currency}")
    int accumulate(@Param("accountId") long accountId, @Param("currency") String currency,
                   @Param("debitDelta") long debitDelta, @Param("creditDelta") long creditDelta,
                   @Param("entryCount") long entryCount, @Param("lastEntryId") long lastEntryId,
                   @Param("now") java.time.Instant now);

    @Insert("INSERT INTO account_balances (account_instance_id, currency, debit_total, credit_total, "
            + "entry_count, last_entry_id, updated_at) "
            + "VALUES (#{accountId}, #{currency}, #{debitDelta}, #{creditDelta}, #{entryCount}, "
            + "#{lastEntryId}, #{now})")
    int insertInitial(@Param("accountId") long accountId, @Param("currency") String currency,
                      @Param("debitDelta") long debitDelta, @Param("creditDelta") long creditDelta,
                      @Param("entryCount") long entryCount, @Param("lastEntryId") long lastEntryId,
                      @Param("now") java.time.Instant now);

    @Delete("DELETE FROM account_balances")
    int deleteAll();

    /** 重建：按分录（Source of Truth）全量聚合回填。 */
    @Insert("INSERT INTO account_balances (account_instance_id, currency, debit_total, credit_total, "
            + "entry_count, last_entry_id, updated_at) "
            + "SELECT e.account_id, e.currency, "
            + "SUM(CASE WHEN e.direction = 'DEBIT' THEN e.amount_minor ELSE 0 END), "
            + "SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount_minor ELSE 0 END), "
            + "COUNT(*), MAX(e.id), CURRENT_TIMESTAMP "
            + "FROM ledger_entries e GROUP BY e.account_id, e.currency")
    int rebuildFromEntries();

    @Select("SELECT COUNT(*) FROM account_balances")
    long count();
}
