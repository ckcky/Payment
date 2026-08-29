import pymysql
# try likely names
candidates = ['merchant','merchants','merchant_service','merchant_db','paymentarch','payment_arch']
for c in candidates:
    try:
        conn = pymysql.connect(host='localhost', port=3306, user='root', password='root', database=c, connect_timeout=3)
        cur = conn.cursor(); cur.execute("SHOW TABLES"); print(c, "->", [r[0] for r in cur.fetchall()])
        conn.close()
    except Exception as e:
        print(c, "ERR", e)
# also list all databases
conn = pymysql.connect(host='localhost', port=3306, user='root', password='root', connect_timeout=5)
cur = conn.cursor(); cur.execute("SHOW DATABASES")
print("ALL DBs:", [r[0] for r in cur.fetchall()])
conn.close()
