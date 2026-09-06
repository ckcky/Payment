#!/usr/bin/env python3
"""
truncate-transactional.py —— 演示环境「轻度复位」：只清空事务表，保留主数据与科目预设。

与 reset.sh 的区别（两者互补，不要混用）：
  - reset.sh                   重度复位：DROP 全部业务库 → 重放 deployment/schema/*.sql → 重灌种子。
                               结果最干净，但耗时较长，且 merchant 的内存仓储会一并清空需重建。
  - truncate-transactional.py  轻度复位：仅 TRUNCATE 各库的事务表，**保留 ledger.accounts 科目预设**
                               与 merchant 内存仓储。适合「刚跑完一轮链路演示，想快速再来一次」。

前置条件：
  1) MySQL 已在 localhost:3306 监听（docker compose 或本机 MySQL 均可），且各业务库已建。
     库不存在时跳过该库（不中断整个脚本）。
  2) 安装依赖：pip install pymysql
  3) 连接参数写死为 root/root（与 deployment/docker-compose.yml 一致），如需修改请改下方 connect()。

用法：
  python deployment/demo/truncate-transactional.py

注意：本脚本会关闭外键检查以绕过 TRUNCATE 的顺序约束，执行期间不要并发写入。
"""

import pymysql

# (db, [tables]) —— 只列事务表；主数据表（如 ledger.accounts、catalog 的字典表）不在此列。
# 注意：Feature 015（ADR-0064）后 refund 库已退役，退款 4 表全部位于 payment 库。
plan = {
    'catalog': ['products', 'skus'],
    'order': ['order_items', 'orders', 'transactions'],
    'payment': [
        'payment_attempts', 'payments',
        'refunds', 'refund_items', 'refund_intake_locks', 'refund_post_process_attempts',
    ],
    'fulfillment': ['fulfillments'],
    'entitlement': ['entitlements'],
    'reconciliation': [
        # 对账差异内嵌在批次行中（无独立 differences 表），只清批次即可
        'reconciliation_batches',
        'audit_batches', 'audit_differences', 'audit_adjustments',
    ],
    'settlement': ['settlement_items', 'settlement_batches'],
    'ledger': ['ledger_entries', 'postings'],
}

conn = pymysql.connect(host='localhost', port=3306, user='root', password='root', connect_timeout=5)
cur = conn.cursor()
cur.execute(
    "SELECT schema_name FROM information_schema.schemata "
    "WHERE schema_name IN (%s)" % ",".join(["%s"] * len(plan)),
    tuple(plan.keys()),
)
existing_dbs = {row[0] for row in cur.fetchall()}
for db, tables in plan.items():
    if db not in existing_dbs:
        print(f"skip {db}: database not found")
        continue
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
