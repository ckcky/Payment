import pymysql
conn = pymysql.connect(host='localhost', port=3306, user='root', password='root', database='order', connect_timeout=5)
cur = conn.cursor()
for t in ['orders','transactions','order_items']:
    try:
        print(f"===== {t} =====")
        cur.execute(f"DESCRIBE {t}")
        for row in cur.fetchall():
            print(row)
    except Exception as e:
        print(f"ERR {t}: {e}")
    print()
conn.close()
