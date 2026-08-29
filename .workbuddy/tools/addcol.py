import pymysql
conn = pymysql.connect(host='localhost', port=3306, user='root', password='root', database='order', connect_timeout=5)
cur = conn.cursor()
try:
    cur.execute("ALTER TABLE orders ADD COLUMN payment_id BIGINT NULL")
    conn.commit()
    print("ALTER OK: added payment_id to orders")
except Exception as e:
    conn.rollback()
    print("ALTER note:", e)
# verify
cur.execute("DESCRIBE orders")
cols = [r[0] for r in cur.fetchall()]
print("orders columns:", cols)
conn.close()
