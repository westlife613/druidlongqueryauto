@echo off
REM 部署到AWS EC2脚本 (Windows版本)

echo ==========================================
echo   部署到AWS EC2 (无Docker版本)
echo ==========================================

REM 配置参数（请根据实际情况修改）
set EC2_HOST=ec2-user@your-ec2-ip-or-hostname
set SSH_KEY=path\to\your-key.pem
set REMOTE_DIR=/home/ec2-user/druid-test

REM 1. Maven打包
echo.
echo 步骤1: Maven打包...
call mvn clean package -DskipTests
if errorlevel 1 (
    echo Maven打包失败
    pause
    exit /b 1
)

REM 2. 检查JAR文件
set JAR_FILE=target\druid-long-query-1.0.0-jar-with-dependencies.jar
if not exist "%JAR_FILE%" (
    echo 错误: JAR文件不存在
    pause
    exit /b 1
)

echo JAR文件: %JAR_FILE%

REM 3. 创建远程目录
echo.
echo 步骤2: 在EC2上创建目录...
ssh -i "%SSH_KEY%" "%EC2_HOST%" "mkdir -p %REMOTE_DIR%"

REM 4. 上传JAR文件
echo.
echo 步骤3: 上传JAR文件到EC2...
scp -i "%SSH_KEY%" "%JAR_FILE%" "%EC2_HOST%:%REMOTE_DIR%/app.jar"

REM 5. 上传运行脚本
echo.
echo 步骤4: 上传运行脚本...
scp -i "%SSH_KEY%" run-on-ec2.sh "%EC2_HOST%:%REMOTE_DIR%/"
ssh -i "%SSH_KEY%" "%EC2_HOST%" "chmod +x %REMOTE_DIR%/run-on-ec2.sh"

echo.
echo ==========================================
echo   部署完成!
echo ==========================================
echo.
echo 登录EC2并运行:
echo   ssh -i %SSH_KEY% %EC2_HOST%
echo   cd %REMOTE_DIR%
echo   ./run-on-ec2.sh
echo.
echo 或使用PuTTY登录后执行:
echo   cd %REMOTE_DIR%
echo   ./run-on-ec2.sh
echo.
pause
