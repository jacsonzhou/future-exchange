# 修复记录 - TC-ACCOUNT-001 数据库密码

- 迭代: 4
- 修复时间: 2026-02-20T12:15:00Z
- 问题: Snapshot Account 查询余额返回 500

## 根因分析
- Nacos 配置中的数据库密码: `root123456`
- MySQL 实际 root 密码: `root`
- 导致数据库连接失败，接口返回 500

## 修复内容
修改 `nacos-configs/snapshot-account-core-dev.yml`:
```yaml
# 修改前
password: root123456

# 修改后  
password: root
```

## 后续步骤
需要重启 Snapshot Account 服务使配置生效
