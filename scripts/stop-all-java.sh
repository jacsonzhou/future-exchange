#!/bin/bash

# 停止所有 Java 进程

echo "正在查找并停止所有 Java 进程..."

# 方法1: 使用 pkill (推荐)
if command -v pkill &> /dev/null; then
    JAVA_PIDS=$(pgrep -f java)
    if [ -n "$JAVA_PIDS" ]; then
        echo "发现以下 Java 进程:"
        ps -fp $JAVA_PIDS
        echo ""
        echo "正在停止这些进程..."
        pkill -f java
        sleep 2
        
        # 检查是否还有残留进程
        REMAINING=$(pgrep -f java)
        if [ -n "$REMAINING" ]; then
            echo "强制停止剩余进程..."
            pkill -9 -f java
        fi
    else
        echo "未发现运行中的 Java 进程"
    fi
# 方法2: 使用 jps (JDK 自带工具)
elif command -v jps &> /dev/null; then
    JAVA_PIDS=$(jps | grep -v Jps | awk '{print $1}')
    if [ -n "$JAVA_PIDS" ]; then
        echo "发现以下 Java 进程:"
        jps
        echo ""
        echo "正在停止这些进程..."
        for pid in $JAVA_PIDS; do
            kill $pid 2>/dev/null
        done
        sleep 2
        
        # 强制停止残留进程
        for pid in $JAVA_PIDS; do
            if kill -0 $pid 2>/dev/null; then
                kill -9 $pid 2>/dev/null
            fi
        done
    else
        echo "未发现运行中的 Java 进程"
    fi
else
    echo "错误: 未找到 pkill 或 jps 命令"
    exit 1
fi

echo ""
echo "✅ 所有 Java 进程已停止"
