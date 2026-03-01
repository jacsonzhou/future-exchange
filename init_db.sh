#!/bin/bash

# 创建数据库

echo "创建交易所数据库..."

mysql -u root -p <<EOF
CREATE DATABASE IF NOT EXISTS exchange_oms DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS exchange_ledger DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS exchange_adl DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;

SHOW DATABASES;

USE exchange_oms;
SOURCE sql/oms_schema.sql;

USE exchange_ledger;
SOURCE sql/ledger_schema.sql;

USE exchange_adl;
SOURCE sql/adl_schema.sql;

SELECT 'Database initialization completed!' as Status;
EOF

echo "✅ 数据库初始化完成"



