-- =============================================================================
-- audit-faults.sql —— spec 017 审计演示故障注入（幂等，可重复执行）
-- =============================================================================
-- ⚠️⚠️⚠️  演示故障数据，仅限本地演示库使用，禁止用于任何生产环境！  ⚠️⚠️⚠️
--
-- 用法（二选一）：
--   1) scenario-audit.sh 自动执行（pymysql 直连 localhost:3306 root/root）
--   2) 手工：mysql -h127.0.0.1 -uroot -proot < audit-faults.sql
--
-- 周期 2026-08-31，固定单号（plan §8.2 F1~F7）：
--   F1 平账基座：PM-AUD-0001(10000) / PM-AUD-0002(25000) / RF-AUD-0001(3000)
--               / SB-AUD-0001(income 35000, refund 3000, net 32000)
--   F2 漏记账  ：PM-AUD-0003(8000) 有支付无分录        → MISSING_POSTING
--   F3 孤儿分录：PM-AUD-GHOST1(5000) 有分录无支付      → ORPHAN_POSTING
--   F4 金额不符：LP-AUD-0001 双边 10000 → 9900        → AMOUNT_MISMATCH
--   F5 重复记账：PM-AUD-0002 再记一条（不同幂等键）    → DUPLICATE_POSTING
--   F6 科目记错：LP-AUD-0002 贷方 应付商户 → 已结算待出款 → ACCOUNT_RECON_BREAK（LEDGER scope）
--   F7 跨账不符：SB-AUD-0001 net 32000 → 31250        → CROSS_LEDGER_MISMATCH（LEDGER scope）
--                账证核对同步报 SETTLEMENT AMOUNT_MISMATCH(expected 31250 / actual 32000)
--   F8 账实不符：渠道长款 CH-AUD-X1(12000) 在 2026-08-31.csv，账本无 → LEDGER_VS_STATEMENT_BREAK（REAL scope）
--   F9 账表不符：需 006 对账批 matches 数据（报表口径），演示默认不注入；
--               REPORT scope 对无 006 批次的周期自动跳过（ReportAuditor 契约）。
--
-- 分录口径与生产记账一致（feeMinor 恒 0）：
--   支付：借 CUSTOMER_CASH(1) / 贷 MERCHANT_PAYABLE(2)
--   退款：借 MERCHANT_PAYABLE(2) / 贷 CUSTOMER_CASH(1)
--   结算：借 MERCHANT_PAYABLE(2) / 贷 SETTLEMENT_PAYABLE(4)，source_id = 批次 id
-- =============================================================================

-- ---- F1：支付事实（payment 库）----
INSERT INTO payment.payment_attempts
    (payment_no, channel_code, attempt_type, requested_at, responded_at, channel_reference, status,
     created_at, updated_at, created_by, updated_by, version)
VALUES
    ('PM-AUD-0001', 'MOCK', 'PAYMENT', NOW(), NOW(), 'CH-AUD-0001', 'SUCCEEDED', NOW(), NOW(), 'audit-fixture', 'audit-fixture', 1),
    ('PM-AUD-0002', 'MOCK', 'PAYMENT', NOW(), NOW(), 'CH-AUD-0002', 'SUCCEEDED', NOW(), NOW(), 'audit-fixture', 'audit-fixture', 1),
    ('PM-AUD-0003', 'MOCK', 'PAYMENT', NOW(), NOW(), 'CH-AUD-0003', 'SUCCEEDED', NOW(), NOW(), 'audit-fixture', 'audit-fixture', 1)
ON DUPLICATE KEY UPDATE channel_reference = VALUES(channel_reference);

INSERT INTO payment.payments
    (payment_no, transaction_id, order_no, user_id, amount_minor, currency_code, idempotency_key,
     attempt_seq, status, current_attempt_id, created_at, updated_at, created_by, updated_by, version)
SELECT t.payment_no, t.txn, t.order_no, 'audit-user', t.amount, 'CNY', t.idem, 1, 'SUCCEEDED',
       (SELECT id FROM payment.payment_attempts a WHERE a.channel_reference = t.chan_ref),
       NOW(), NOW(), 'audit-fixture', 'audit-fixture', 1
FROM (
    SELECT 'PM-AUD-0001' AS payment_no, 'TXN-AUD-0001' AS txn, 'OR-AUD-0001' AS order_no,
           10000 AS amount, 'audit-fx-pm-0001' AS idem, 'CH-AUD-0001' AS chan_ref
    UNION ALL
    SELECT 'PM-AUD-0002', 'TXN-AUD-0002', 'OR-AUD-0002', 25000, 'audit-fx-pm-0002', 'CH-AUD-0002'
    UNION ALL
    SELECT 'PM-AUD-0003', 'TXN-AUD-0003', 'OR-AUD-0003', 8000, 'audit-fx-pm-0003', 'CH-AUD-0003'
) t
ON DUPLICATE KEY UPDATE payment_no = payment.payments.payment_no;

-- ---- F1：退款事实（RF-AUD-0001，冲 PM-AUD-0001）----
INSERT INTO payment.payment_attempts
    (payment_no, channel_code, attempt_type, requested_at, responded_at, channel_reference, status,
     created_at, updated_at, created_by, updated_by, version)
VALUES
    ('PM-AUD-0001', 'MOCK', 'REFUND', NOW(), NOW(), 'CH-RF-0001', 'SUCCEEDED', NOW(), NOW(), 'audit-fixture', 'audit-fixture', 1)
ON DUPLICATE KEY UPDATE channel_reference = VALUES(channel_reference);

INSERT INTO payment.refunds
    (refund_no, order_no, payment_no, user_id, amount_minor, currency_code, reason, idempotency_key,
     status, created_at, updated_at, created_by, updated_by, version)
VALUES
    ('RF-AUD-0001', 'OR-AUD-0001', 'PM-AUD-0001', 'audit-user', 3000, 'CNY', 'audit-fixture-refund',
     'audit-fx-rf-0001', 'SUCCEEDED', NOW(), NOW(), 'audit-fixture', 'audit-fixture', 1)
ON DUPLICATE KEY UPDATE refund_no = payment.refunds.refund_no;

-- ---- F1：结算批次（settlement 库；net = 35000 − 3000 = 32000）----
INSERT INTO settlement.settlement_batches
    (batch_no, merchant_id, period, currency_code, income_minor, refund_minor, adjustment_minor,
     net_minor, status, idempotency_key, fact_count, created_at, updated_at, created_by, updated_by, version)
VALUES
    ('SB-AUD-0001', '1', '2026-08-31', 'CNY', 35000, 3000, 0, 32000, 'SUCCEEDED',
     'audit-fx-sb-0001', 3, NOW(), NOW(), 'audit-fixture', 'audit-fixture', 1)
ON DUPLICATE KEY UPDATE batch_no = settlement.settlement_batches.batch_no;

SET @sb := (SELECT id FROM settlement.settlement_batches WHERE batch_no = 'SB-AUD-0001');

INSERT INTO settlement.settlement_items (batch_id, reference, type, amount_minor, currency_code,
                                         created_at, updated_at, created_by, updated_by, version)
SELECT @sb, x.reference, x.type, x.amount, 'CNY', NOW(), NOW(), 'audit-fixture', 'audit-fixture', 1
FROM (
    SELECT 'PM-AUD-0001' AS reference, 'INCOME' AS type, 10000 AS amount
    UNION ALL SELECT 'PM-AUD-0002', 'INCOME', 25000
    UNION ALL SELECT 'RF-AUD-0001', 'REFUND', 3000
) x
WHERE NOT EXISTS (SELECT 1 FROM settlement.settlement_items i
                  WHERE i.batch_id = @sb AND i.reference = x.reference AND i.type = x.type);

-- ---- F1/F3/F5：账本分录（ledger 库）----
-- F1 支付分录（借1/贷2，无手续费拆分，与生产 feeMinor=0 口径一致）
INSERT INTO ledger.postings
    (posting_no, idempotency_key, source_type, source_id, status, currency,
     created_at, created_by, updated_at, updated_by, version)
VALUES
    ('LP-AUD-0001', 'audit-fx-lp-0001', 'PAYMENT', 'PM-AUD-0001', 'POSTED', 'CNY', NOW(), 'audit-fixture', NOW(), 'audit-fixture', 1),
    ('LP-AUD-0002', 'audit-fx-lp-0002', 'PAYMENT', 'PM-AUD-0002', 'POSTED', 'CNY', NOW(), 'audit-fixture', NOW(), 'audit-fixture', 1),
    ('LP-AUD-0003', 'audit-fx-lp-0003', 'REFUND',  'RF-AUD-0001', 'POSTED', 'CNY', NOW(), 'audit-fixture', NOW(), 'audit-fixture', 1)
ON DUPLICATE KEY UPDATE posting_no = ledger.postings.posting_no;

-- F5 重复记账：PM-AUD-0002 再记一条（幂等键不同 → 幂等被击穿）
INSERT INTO ledger.postings
    (posting_no, idempotency_key, source_type, source_id, status, currency,
     created_at, created_by, updated_at, updated_by, version)
VALUES
    ('LP-AUD-0002D', 'audit-fault-f5-dup', 'PAYMENT', 'PM-AUD-0002', 'POSTED', 'CNY', NOW(), 'audit-fixture', NOW(), 'audit-fixture', 1)
ON DUPLICATE KEY UPDATE posting_no = ledger.postings.posting_no;

-- F3 孤儿分录：业务侧无 PM-AUD-GHOST1 支付
INSERT INTO ledger.postings
    (posting_no, idempotency_key, source_type, source_id, status, currency,
     created_at, created_by, updated_at, updated_by, version)
VALUES
    ('LP-AUD-GHOST1', 'audit-fault-f3-orphan', 'PAYMENT', 'PM-AUD-GHOST1', 'POSTED', 'CNY', NOW(), 'audit-fixture', NOW(), 'audit-fixture', 1)
ON DUPLICATE KEY UPDATE posting_no = ledger.postings.posting_no;

-- 分录行（append-only 无唯一键 → NOT EXISTS 判存）
-- 幂等口径：posting 由幂等键唯一约束兜底；分录以「该 posting 已有任何分录」整体判存，
-- 避免 F4/F6 的 UPDATE 篡改后重跑时 NOT EXISTS 失配而重插（会破坏借贷平衡）。
INSERT INTO ledger.ledger_entries
    (posting_id, account_id, direction, amount_minor, currency, entry_type, source_type, source_id, created_at)
SELECT p.id, e.account_id, e.direction, e.amount, 'CNY', e.entry_type, p.source_type, p.source_id, NOW()
FROM ledger.postings p
JOIN (
    SELECT 'audit-fx-lp-0001' AS ik, 1 AS account_id, 'DEBIT'  AS direction, 10000 AS amount, 'PAYMENT_CAPTURE' AS entry_type
    UNION ALL SELECT 'audit-fx-lp-0001', 2, 'CREDIT', 10000, 'PAYMENT_CAPTURE'
    UNION ALL SELECT 'audit-fx-lp-0002', 1, 'DEBIT',  25000, 'PAYMENT_CAPTURE'
    UNION ALL SELECT 'audit-fx-lp-0002', 2, 'CREDIT', 25000, 'PAYMENT_CAPTURE'
    UNION ALL SELECT 'audit-fx-lp-0003', 2, 'DEBIT',   3000, 'REFUND'
    UNION ALL SELECT 'audit-fx-lp-0003', 1, 'CREDIT',  3000, 'REFUND'
    UNION ALL SELECT 'audit-fault-f5-dup', 1, 'DEBIT',  25000, 'PAYMENT_CAPTURE'
    UNION ALL SELECT 'audit-fault-f5-dup', 2, 'CREDIT', 25000, 'PAYMENT_CAPTURE'
    UNION ALL SELECT 'audit-fault-f3-orphan', 1, 'DEBIT',  5000, 'PAYMENT_CAPTURE'
    UNION ALL SELECT 'audit-fault-f3-orphan', 2, 'CREDIT', 5000, 'PAYMENT_CAPTURE'
) e ON e.ik = p.idempotency_key
WHERE NOT EXISTS (SELECT 1 FROM ledger.ledger_entries x WHERE x.posting_id = p.id LIMIT 1);

-- 结算分录：借2 32000 / 贷4 32000，source_id = 批次 id（跨账核对键）
INSERT INTO ledger.postings
    (posting_no, idempotency_key, source_type, source_id, status, currency,
     created_at, created_by, updated_at, updated_by, version)
VALUES
    ('LP-AUD-0004', 'audit-fx-lp-0004', 'SETTLEMENT', CAST(@sb AS CHAR), 'POSTED', 'CNY', NOW(), 'audit-fixture', NOW(), 'audit-fixture', 1)
ON DUPLICATE KEY UPDATE posting_no = ledger.postings.posting_no;

INSERT INTO ledger.ledger_entries
    (posting_id, account_id, direction, amount_minor, currency, entry_type, source_type, source_id, created_at)
SELECT p.id, e.account_id, e.direction, e.amount, 'CNY', 'SETTLEMENT', p.source_type, p.source_id, NOW()
FROM ledger.postings p
JOIN (
    SELECT 1 AS account_id, 'DEBIT' AS direction, 32000 AS amount
    UNION ALL SELECT 4, 'CREDIT', 32000
) e
WHERE p.idempotency_key = 'audit-fx-lp-0004'
  AND NOT EXISTS (SELECT 1 FROM ledger.ledger_entries x WHERE x.posting_id = p.id LIMIT 1);

-- ---- F4：金额不符（双边篡改，保持分录自平衡；只在初始值时改 → 幂等）----
UPDATE ledger.ledger_entries
SET amount_minor = 9900
WHERE posting_id = (SELECT id FROM ledger.postings WHERE idempotency_key = 'audit-fx-lp-0001')
  AND amount_minor = 10000;

-- ---- F6：科目记错（贷方 应付商户 → 已结算待出款；只在初始科目时改 → 幂等）----
UPDATE ledger.ledger_entries
SET account_id = 4
WHERE posting_id = (SELECT id FROM ledger.postings WHERE idempotency_key = 'audit-fx-lp-0002')
  AND account_id = 2 AND direction = 'CREDIT' AND amount_minor = 25000;

-- ---- F7：跨账不符（批次净额 32000 → 31200；items 同步改保持批次内自洽；幂等）----
UPDATE settlement.settlement_batches
SET net_minor = 31250, income_minor = 34250
WHERE batch_no = 'SB-AUD-0001' AND net_minor = 32000;

UPDATE settlement.settlement_items
SET amount_minor = 9250
WHERE batch_id = @sb AND reference = 'PM-AUD-0001' AND type = 'INCOME' AND amount_minor = 10000;

SELECT 'audit-faults applied (F1~F7). F8 via 2026-08-31.csv, F9 not injected (see header).' AS result;
