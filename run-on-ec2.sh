#!/bin/bash

# 在EC2实例上运行的脚本
# 此脚本应该已通过deploy-to-ec2.sh上传到EC2

set -e

echo "=========================================="
echo "  在EC2上运行Druid测试工具"
echo "=========================================="

# 检查Java是否安装
if ! command -v java &> /dev/null; then
    echo "Java未安装，正在安装Amazon Corretto 8..."
    sudo yum install -y java-1.8.0-amazon-corretto-devel
fi

echo ""
echo "Java版本:"
java -version

# 检查JAR文件
if [ ! -f "app.jar" ]; then
    echo "错误: app.jar 不存在"
    exit 1
fi

# 设置环境变量
export AWS_REGION=$(ec2-metadata --availability-zone | cut -d " " -f 2 | sed 's/[a-z]$//')
export EC2_INSTANCE_ID=$(ec2-metadata --instance-id | cut -d " " -f 2)

echo ""
echo "AWS Region: $AWS_REGION"
echo "EC2 Instance ID: $EC2_INSTANCE_ID"

# 运行应用
echo ""
echo "启动应用..."
echo "----------------------------------------"

java -jar app.jar

echo ""
echo "=========================================="
echo "  执行完成"
echo "=========================================="
