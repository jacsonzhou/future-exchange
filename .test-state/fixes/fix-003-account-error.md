# 问题分析 - TC-ACCOUNT-001

## 问题
查询余额返回 HTTP 500
- 请求: GET /internal/snapshot/account/13
- 响应: {"status":500,"error":"Internal Server Error"}

## 可能原因
1. 数据库连接问题
2. 用户 13 不存在于 snapshot_account 表
3. Redis 连接失败
4. 其他内部异常

## 排查建议
1. 检查 Snapshot Account 服务日志
2. 确认数据库表是否存在
3. 检查 user_id=13 的数据是否存在

## 临时解决方案
- 标记为 FAILED，等待数据初始化或修复数据库
