#!/bin/bash
# ============================================
# Nacos 配置导入脚本
# 用于将本地 YAML 配置文件导入到 Nacos 配置中心
# ============================================

# Nacos 服务器地址
NACOS_SERVER="${NACOS_SERVER:-localhost:8848}"
NAMESPACE_NAME="${NACOS_NAMESPACE:-exchange}"
GROUP_NAME="${NACOS_GROUP:-DEFAULT_GROUP}"
NACOS_USERNAME="${NACOS_USERNAME:-nacos}"
NACOS_PASSWORD="${NACOS_PASSWORD:-nacos}"

# 命名空间名称到ID的映射
# 注意: Nacos API 需要使用 namespaceId (UUID)，而不是 namespace 名称
# 格式: "名称:ID"
NAMESPACE_MAPPINGS="
public:
exchange:955daf97-50be-4155-9bb5-1bbd0ddcfa7c
"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "=========================================="
echo "Nacos 配置导入工具"
echo "=========================================="
echo "服务器: $NACOS_SERVER"
echo "命名空间: $NAMESPACE_NAME"
echo "分组: $GROUP_NAME"
echo "用户名: $NACOS_USERNAME"
echo "=========================================="

# 检查 curl 是否安装
if ! command -v curl &> /dev/null; then
    echo -e "${RED}错误: curl 未安装${NC}"
    exit 1
fi

# 检查 Nacos 是否可连接
if ! curl -s "http://$NACOS_SERVER/nacos" > /dev/null; then
    echo -e "${RED}错误: 无法连接到 Nacos 服务器 ($NACOS_SERVER)${NC}"
    exit 1
fi

# 获取 Nacos accessToken
NACOS_TOKEN=$(curl -s -X POST "http://$NACOS_SERVER/nacos/v1/auth/users/login" \
    -d "username=$NACOS_USERNAME" \
    -d "password=$NACOS_PASSWORD" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)

if [ -z "$NACOS_TOKEN" ]; then
    echo -e "${YELLOW}警告: 无法获取 accessToken，尝试使用无认证模式${NC}"
    NACOS_TOKEN=""
fi

# 获取命名空间ID (从映射中查找)
NAMESPACE_ID=$(echo "$NAMESPACE_MAPPINGS" | grep "^$NAMESPACE_NAME:" | cut -d':' -f2)
if [ -z "$NAMESPACE_ID" ] && [ "$NAMESPACE_NAME" != "public" ]; then
    echo -e "${YELLOW}警告: 未知的命名空间 '$NAMESPACE_NAME'，使用名称作为ID${NC}"
    NAMESPACE_ID="$NAMESPACE_NAME"
fi

echo "命名空间ID: $NAMESPACE_ID"
echo "=========================================="

# 导入单个配置文件的函数
import_config() {
    local file=$1
    local data_id=$(basename "$file")
    
    echo -n "正在导入 $data_id ... "
    
    # 将文件内容保存到临时文件，用于 --data-urlencode
    local tmpfile=$(mktemp)
    cat "$file" > "$tmpfile"
    
    # 构建 curl 请求
    local curl_cmd="curl -s -X POST \"http://$NACOS_SERVER/nacos/v1/cs/configs\""
    
    # 添加认证 token
    if [ -n "$NACOS_TOKEN" ]; then
        curl_cmd="$curl_cmd -d \"accessToken=$NACOS_TOKEN\""
    fi
    
    # 添加表单数据 (使用 --data-urlencode 正确编码)
    curl_cmd="$curl_cmd --data-urlencode \"dataId=$data_id\""
    curl_cmd="$curl_cmd --data-urlencode \"group=$GROUP_NAME\""
    if [ -n "$NAMESPACE_ID" ]; then
        curl_cmd="$curl_cmd --data-urlencode \"namespaceId=$NAMESPACE_ID\""
    fi
    curl_cmd="$curl_cmd --data-urlencode \"content@$tmpfile\""
    curl_cmd="$curl_cmd -w \"\\n%{http_code}\""
    
    local response=$(eval $curl_cmd)
    local exit_code=$?
    
    # 删除临时文件
    rm -f "$tmpfile"
    
    local http_code=$(echo "$response" | tail -n1)
    local body=$(echo "$response" | sed '$d')
    
    if [ "$http_code" = "200" ] && [ "$body" = "true" ]; then
        echo -e "${GREEN}成功${NC}"
        return 0
    else
        echo -e "${RED}失败 (HTTP $http_code: $body)${NC}"
        return 1
    fi
}

# 统计
success_count=0
fail_count=0

# 遍历所有 yml 文件
for file in *.yml; do
    if [ -f "$file" ] && [ "$file" != "README.md" ]; then
        if import_config "$file"; then
            ((success_count++))
        else
            ((fail_count++))
        fi
    fi
done

echo "=========================================="
echo -e "导入完成: ${GREEN}成功 $success_count${NC}, ${RED}失败 $fail_count${NC}"
echo "=========================================="

if [ $fail_count -gt 0 ]; then
    exit 1
fi
