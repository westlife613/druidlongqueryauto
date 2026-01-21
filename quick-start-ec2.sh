#!/bin/bash

# EC2 快速启动脚本
# 此脚本包含了从S3下载到运行的完整流程

echo "=========================================="
echo "  Druid测试工具 - EC2快速启动"
echo "=========================================="

# 配置参数 - 请修改为你的S3存储桶名称
S3_BUCKET="druid-test-upload-yourname"
JAR_FILE="druid-long-query-1.0.0-jar-with-dependencies.jar"

# 颜色输出
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${YELLOW}步骤1: 检查Java环境${NC}"
if ! command -v java &> /dev/null; then
    echo "Java未安装，正在安装..."
    sudo yum install -y java-1.8.0-amazon-corretto-devel
else
    echo -e "${GREEN}✓ Java已安装${NC}"
fi

echo ""
java -version

echo ""
echo -e "${YELLOW}步骤2: 从S3下载JAR文件${NC}"
echo "存储桶: s3://${S3_BUCKET}/${JAR_FILE}"

if aws s3 cp "s3://${S3_BUCKET}/${JAR_FILE}" ./app.jar; then
    echo -e "${GREEN}✓ 文件下载成功${NC}"
else
    echo -e "${RED}✗ 文件下载失败${NC}"
    echo ""
    echo "可能的原因："
    echo "1. S3存储桶名称不正确（当前: ${S3_BUCKET}）"
    echo "2. EC2实例没有S3访问权限（需要IAM角色）"
    echo "3. 文件不存在"
    echo ""
    echo "解决方法："
    echo "1. 编辑此脚本，修改 S3_BUCKET 变量"
    echo "2. 在EC2控制台为实例添加IAM角色（AmazonS3ReadOnlyAccess）"
    echo ""
    exit 1
fi

echo ""
echo -e "${YELLOW}步骤3: 检查文件${NC}"
if [ -f "app.jar" ]; then
    echo -e "${GREEN}✓ app.jar 存在${NC}"
    ls -lh app.jar
else
    echo -e "${RED}✗ app.jar 不存在${NC}"
    exit 1
fi

echo ""
echo -e "${YELLOW}步骤4: 获取AWS环境信息${NC}"
AWS_REGION=$(ec2-metadata --availability-zone 2>/dev/null | cut -d " " -f 2 | sed 's/[a-z]$//' || echo "未知")
EC2_INSTANCE_ID=$(ec2-metadata --instance-id 2>/dev/null | cut -d " " -f 2 || echo "未知")
echo "AWS Region: ${AWS_REGION}"
echo "EC2 Instance ID: ${EC2_INSTANCE_ID}"

echo ""
echo "=========================================="
echo "  准备完成，即将启动程序"
echo "=========================================="
echo ""
echo "请选择运行模式："
echo "1) 前台运行（可以看到实时输出，Ctrl+C停止）"
echo "2) 后台运行（输出保存到 output.log）"
echo ""
read -p "请输入选项 [1/2]: " choice

case $choice in
    1)
        echo ""
        echo "前台运行中..."
        echo "按 Ctrl+C 停止程序"
        echo ""
        java -jar app.jar
        ;;
    2)
        echo ""
        echo "后台运行中..."
        nohup java -jar app.jar > output.log 2>&1 &
        PID=$!
        echo -e "${GREEN}✓ 程序已启动，进程ID: ${PID}${NC}"
        echo ""
        echo "查看日志: tail -f output.log"
        echo "查看进程: ps aux | grep java"
        echo "停止程序: kill ${PID}"
        echo ""
        echo "等待3秒后显示初始日志..."
        sleep 3
        echo ""
        echo "========== 最新日志 =========="
        tail -n 20 output.log
        echo "============================="
        echo ""
        echo "继续查看日志: tail -f output.log"
        ;;
    *)
        echo "无效选项，默认前台运行"
        java -jar app.jar
        ;;
esac

echo ""
echo "=========================================="
echo "  程序已结束"
echo "=========================================="
