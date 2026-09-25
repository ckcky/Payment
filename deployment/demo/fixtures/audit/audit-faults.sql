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
-- 注：018 迁移后 payment_attempts.amount_minor / currency_code 为 NOT NULL，注入须显式赋值（否则 1364）。
-- 注：037 起 payment_attempts.channel_no 为 NOT NULL + UNIQUE（渠道网关业务单号 CH+雪花），
--     注入须显式赋值（否则 1364）；此处用 CH-AUD-* 形态的确定性占位值，与 channel_reference 区分。
-- 注：032 起 confirmed-facts(period) 按 DATE(created_at) 过滤（C-20 事实锚）——created_at 必须落在
--     AUDIT_PERIOD（2026-08-31）内，否则演示事实为空、F2~F7 全部隐身；merchant_id 为 032 匹配键
--     （G2/G4）与结算口径商户校验（ConfirmedFactGate）的必填锚，统一挂商户 '1'。
INSERT INTO payment.payment_attempts
    (payment_no, channel_no, channel_code, attempt_type, amount_minor, currency_code, requested_at, responded_at, channel_reference, status,
     created_at, updated_at, created_by, updated_by, version)
VALUES
    ('PM-AUD-0001', 'CH-AUD-PM-0001', 'MOCK', 'PAYMENT', 10000, 'CNY', '2026-08-31 10:00:00', '2026-08-31 10:00:05', 'CH-AUD-0001', 'SUCCEEDED', '2026-08-31 10:00:00', '2026-08-31 10:00:05', 'audit-fixture', 'audit-fixture', 1),
    ('PM-AUD-0002', 'CH-AUD-PM-0002', 'MOCK', 'PAYMENT', 25000, 'CNY', '2026-08-31 10:01:00', '2026-08-31 10:01:05', 'CH-AUD-0002', 'SUCCEEDED', '2026-08-31 10:01:00', '2026-08-31 10:01:05', 'audit-fixture', 'audit-fixture', 1),
    ('PM-AUD-0003', 'CH-AUD-PM-0003', 'MOCK', 'PAYMENT', 8000, 'CNY', '2026-08-31 10:02:00', '2026-08-31 10:02:05', 'CH-AUD-0003', 'SUCCEEDED', '2026-08-31 10:02:00', '2026-08-31 10:02:05', 'audit-fixture', 'audit-fixture', 1)
ON DUPLICATE KEY UPDATE channel_no = VALUES(channel_no), channel_reference = VALUES(channel_reference), amount_minor = VALUES(amount_minor), currency_code = VALUES(currency_code),
    created_at = VALUES(created_at), updated_at = VALUES(updated_at);

INSERT INTO payment.payments
    (payment_no, transaction_id, order_no, user_id, merchant_id, amount_minor, currency_code, idempotency_key,
     attempt_seq, status, current_attempt_id, created_at, updated_at, created_by, updated_by, version)
SELECT t.payment_no, t.txn, t.order_no, 'audit-user', '1', t.amount, 'CNY', t.idem, 1, 'SUCCEEDED',
       (SELECT id FROM payment.payment_attempts a WHERE a.channel_reference = t.chan_ref),
       '2026-08-31 10:00:00', '2026-08-31 10:00:05', 'audit-fixture', 'audit-fixture', 1
FROM (
    SELECT 'PM-AUD-0001' AS payment_no, 'TXN-AUD-0001' AS txn, 'OR-AUD-0001' AS order_no,
           10000 AS amount, 'audit-fx-pm-0001' AS idem, 'CH-AUD-0001' AS chan_ref
    UNION ALL
    SELECT 'PM-AUD-0002', 'TXN-AUD-0002', 'OR-AUD-0002', 25000, 'audit-fx-pm-0002', 'CH-AUD-0002'
    UNION ALL
    SELECT 'PM-AUD-0003', 'TXN-AUD-0003', 'OR-AUD-0003', 8000, 'audit-fx-pm-0003', 'CH-AUD-0003'
) t
ON DUPLICATE KEY UPDATE merchant_id = VALUES(merchant_id), created_at = VALUES(created_at), updated_at = VALUES(updated_at);

-- ---- F1：退款事实（RF-AUD-0001，冲 PM-AUD-0001）----
INSERT INTO payment.payment_attempts
    (payment_no, channel_no, channel_code, attempt_type, amount_minor, currency_code, requested_at, responded_at, channel_reference, status,
     created_at, updated_at, created_by, updated_by, version)
VALUES
    ('PM-AUD-0001', 'CH-AUD-RF-0001', 'MOCK', 'REFUND', 3000, 'CNY', '2026-08-31 11:00:00', '2026-08-31 11:00:05', 'CH-RF-0001', 'SUCCEEDED', '2026-08-31 11:00:00', '2026-08-31 11:00:05', 'audit-fixture', 'audit-fixture', 1)
ON DUPLICATE KEY UPDATE channel_no = VALUES(channel_no), channel_reference = VALUES(channel_reference), amount_minor = VALUES(amount_minor), currency_code = VALUES(currency_code),
    created_at = VALUES(created_at), updated_at = VALUES(updated_at);

INSERT INTO payment.refunds
    (refund_no, order_no, payment_no, user_id, amount_minor, currency_code, reason, idempotency_key,
     status, created_at, updated_at, created_by, updated_by, version)
VALUES
    ('RF-AUD-0001', 'OR-AUD-0001', 'PM-AUD-0001', 'audit-user', 3000, 'CNY', 'audit-fixture-refund',
     'audit-fx-rf-0001', 'SUCCEEDED', '2026-08-31 11:00:00', '2026-08-31 11:00:05', 'audit-fixture', 'audit-fixture', 1)
ON DUPLICATE KEY UPDATE created_at = VALUES(created_at), updated_at = VALUES(updated_at);

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
-- 031：postings 新增 NOT NULL event_type / period(YYYY-MM) / posted_at；追溯走 posting_id↔postings，
--       ledger_entries 的 entry_type/source_* 已停写（此处不再依赖）。event_type↔source_type 映射：
--       PAYMENT_CAPTURE→PAYMENT、REFUND→REFUND、MERCHANT_SETTLEMENT→SETTLEMENT。
INSERT INTO ledger.postings
    (posting_no, idempotency_key, source_type, source_id, event_type, period, posted_at, status, currency,
     created_at, created_by, updated_at, updated_by, version)
VALUES
    ('LP-AUD-0001', 'audit-fx-lp-0001', 'PAYMENT', 'PM-AUD-0001', 'PAYMENT_CAPTURE', '2026-08', NOW(), 'POSTED', 'CNY', NOW(), 'audit-fixture', NOW(), 'audit-fixture', 1),
    ('LP-AUD-0002', 'audit-fx-lp-0002', 'PAYMENT', 'PM-AUD-0002', 'PAYMENT_CAPTURE', '2026-08', NOW(), 'POSTED', 'CNY', NOW(), 'audit-fixture', NOW(), 'audit-fixture', 1),
    ('LP-AUD-0003', 'audit-fx-lp-0003', 'REFUND',  'RF-AUD-0001', 'REFUND',          '2026-08', NOW(), 'POSTED', 'CNY', NOW(), 'audit-fixture', NOW(), 'audit-fixture', 1)
ON DUPLICATE KEY UPDATE posting_no = ledger.postings.posting_no;

-- F5 重复记账：PM-AUD-0002 再记一条（同 source_type|source_id → 命中 CertificateAuditor 的
-- DUPLICATE_POSTING；event_type 取 REFUND 以躲开 031 的 uk_event_source(event_type,source_id)，
-- 幂等键亦不同 → 演示「幂等被击穿」）
INSERT INTO ledger.postings
    (posting_no, idempotency_key, source_type, source_id, event_type, period, posted_at, status, currency,
     created_at, created_by, updated_at, updated_by, version)
VALUES
    ('LP-AUD-0002D', 'audit-fault-f5-dup', 'PAYMENT', 'PM-AUD-0002', 'REFUND', '2026-08', NOW(), 'POSTED', 'CNY', NOW(), 'audit-fixture', NOW(), 'audit-fixture', 1)
ON DUPLICATE KEY UPDATE posting_no = ledger.postings.posting_no;

-- F3 孤儿分录：业务侧无 PM-AUD-GHOST1 支付
INSERT INTO ledger.postings
    (posting_no, idempotency_key, source_type, source_id, event_type, period, posted_at, status, currency,
     created_at, created_by, updated_at, updated_by, version)
VALUES
    ('LP-AUD-GHOST1', 'audit-fault-f3-orphan', 'PAYMENT', 'PM-AUD-GHOST1', 'PAYMENT_CAPTURE', '2026-08', NOW(), 'POSTED', 'CNY', NOW(), 'audit-fixture', NOW(), 'audit-fixture', 1)
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

-- 结算分录：借2 32000 / 贷4 32000，source_id = 批次号 batchNo（031 M1 收编：数值 batchId 不出服务边界，
-- CertificateAuditor 按 (source_type='SETTLEMENT', source_id=batch_no) 匹配 settlement 事实）
INSERT INTO ledger.postings
    (posting_no, idempotency_key, source_type, source_id, event_type, period, posted_at, status, currency,
     created_at, created_by, updated_at, updated_by, version)
VALUES
    ('LP-AUD-0004', 'audit-fx-lp-0004', 'SETTLEMENT', 'SB-AUD-0001', 'MERCHANT_SETTLEMENT', '2026-08', NOW(), 'POSTED', 'CNY', NOW(), 'audit-fixture', NOW(), 'audit-fixture', 1)
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
