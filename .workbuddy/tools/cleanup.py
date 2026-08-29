import pymysql
# (db, [tables]) - truncate transactional tables only; keep ledger.accounts presets
plan = {
    'catalog': ['products', 'skus'],
    'order': ['order_items', 'orders', 'transactions'],
    'payment': ['payment_attempts', 'payments'],
    'refund': ['refund_items', 'refunds', 'refund_intake_locks'],
    'fulfillment': ['fulfillments'],
    'entitlement': ['entitlements'],
    'reconciliation': ['reconciliation_differences', 'reconciliation_batches'],
    'settlement': ['settlement_items', 'settlement_batches'],
    'ledger': ['ledger_entries', 'postings'],
}
conn = pymysql.connect(host='localhost', port=3306, user='root', password='root', connect_timeout=5)
cur = conn.cursor()
for db, tables in plan.items():
    cur.execute(f"USE `{db}`")
    cur.execute("SET FOREIGN_KEY_CHECKS=0")
    for t in tables:
        try:
            cur.execute(f"TRUNCATE TABLE `{t}`")
            print(f"truncated {db}.{t}")
        except Exception as e:
            print(f"skip {db}.{t}: {e}")
    cur.execute("SET FOREIGN_KEY_CHECKS=1")
conn.commit()
conn.close()
print("CLEANUP DONE")
