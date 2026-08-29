import pymysql
dbs = ['merchant','catalog','order','payment','refund','fulfillment','entitlement','reconciliation','settlement','ledger']
conn = pymysql.connect(host='localhost', port=3306, user='root', password='root', connect_timeout=5)
cur = conn.cursor()
for db in dbs:
    try:
        cur.execute(f"USE `{db}`")
        cur.execute("SHOW TABLES")
        tables = [r[0] for r in cur.fetchall()]
        print(f"=== {db} === {tables}")
    except Exception as e:
        print(f"=== {db} === ERR {e}")
conn.close()
