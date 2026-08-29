import pymysql
conn = pymysql.connect(host='localhost', port=3306, user='root', password='root', database='payment', connect_timeout=5)
cur = conn.cursor()
for t in ['payments', 'payment_attempts']:
    print(f"===== {t} =====")
    cur.execute(f"DESCRIBE {t}")
    for row in cur.fetchall():
        print(row)
    print()
conn.close()
