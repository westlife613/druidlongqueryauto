#!/bin/bash

# AWS EC2部署脚本（不使用Docker）
# 使用方法: ./deploy-to-ec2.sh

set -e

echo "=========================================="
echo "  部署到AWS EC2 (无Docker版本)"
echo "=========================================="

# 配置参数（请根据实际情况修改）
EC2_HOST="ec2-user@your-ec2-ip-or-hostname"
SSH_KEY="path/to/your-key.pem"
REMOTE_DIR="/home/ec2-user/druid-test"

# 1. Maven打包
echo ""
echo "步骤1: Maven打包..."
mvn clean package -DskipTests

# 2. 检查JAR文件
JAR_FILE="target/druid-long-query-1.0.0-jar-with-dependencies.jar"
if [ ! -f "$JAR_FILE" ]; then
    echo "错误: JAR文件不存在"
    exit 1
fi

echo "JAR文件大小: $(du -h $JAR_FILE | cut -f1)"

# 3. SSH连接到EC2并创建目录
echo ""
echo "步骤2: 在EC2上创建目录..."
ssh -i "$SSH_KEY" "$EC2_HOST" "mkdir -p $REMOTE_DIR"

# 4. 上传JAR文件
echo ""
echo "步骤3: 上传JAR文件到EC2..."
scp -i "$SSH_KEY" "$JAR_FILE" "$EC2_HOST:$REMOTE_DIR/app.jar"

# 5. 上传运行脚本
echo ""
echo "步骤4: 上传运行脚本..."
scp -i "$SSH_KEY" run-on-ec2.sh "$EC2_HOST:$REMOTE_DIR/"
ssh -i "$SSH_KEY" "$EC2_HOST" "chmod +x $REMOTE_DIR/run-on-ec2.sh"

echo ""
echo "=========================================="
echo "  部署完成!"
echo "=========================================="
echo ""
echo "登录EC2并运行:"
echo "  ssh -i $SSH_KEY $EC2_HOST"
echo "  cd $REMOTE_DIR"
echo "  ./run-on-ec2.sh"
echo ""
echo "或直接远程执行:"
echo "  ssh -i $SSH_KEY $EC2_HOST 'cd $REMOTE_DIR && ./run-on-ec2.sh'"
echo ""
