@echo off
REM Windows 快速构建脚本

echo ================================
echo 合约交易系统 - 快速构建
echo ================================

echo.
echo 1. 检查Java版本...
java -version
if %errorlevel% neq 0 (
    echo ❌ Java未安装，请先安装Java 17+
    exit /b 1
)

echo.
echo 2. 检查Maven...
mvn -version
if %errorlevel% neq 0 (
    echo ❌ Maven未安装，请先安装Maven 3.8+
    exit /b 1
)

echo.
echo 3. 清理旧的编译文件...
call mvn clean

echo.
echo 4. 编译项目（跳过测试）...
call mvn package -DskipTests

if %errorlevel% equ 0 (
    echo.
    echo ✅ 编译成功！
    echo.
    echo ================================
    echo 下一步操作：
    echo ================================
    echo.
    echo 1. 启动基础设施
    echo 2. 初始化数据库
    echo 3. 启动服务
    echo 4. 测试下单
    echo.
    echo 详见 README.md
) else (
    echo.
    echo ❌ 编译失败，请检查错误信息
    exit /b 1
)

pause







