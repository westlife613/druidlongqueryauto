# AWS Aurora 部署指南

## 📋 前提条件

- AWS 账户
- 已有 Aurora MySQL 集群（或参考步骤1创建）
- EC2 实例（或参考步骤2创建）
- 本地已安装 Maven 和 Java

---

## 🚀 部署步骤

### 步骤 1: 获取 Aurora 连接信息

1. **登录 AWS Console → RDS**
2. **找到您的 Aurora 集群**
3. **复制连接信息**：
   ```
   Writer Endpoint: your-cluster.cluster-xxxxx.ap-southeast-2.rds.amazonaws.com
   Port: 3306
   Database Name: your_database
   Username: admin
   Password: [您的密码]
   ```

### 步骤 2: 准备 EC2 实例

#### 2.1 创建或选择 EC2 实例
- **推荐配置**: t3.small 或更高
- **操作系统**: Amazon Linux 2023 或 Ubuntu
- **安全组**: 确保可以访问 Aurora（端口 3306）

#### 2.2 配置安全组规则

**EC2 安全组**（出站规则）:
```
Type: MySQL/Aurora
Protocol: TCP
Port: 3306
Destination: Aurora 安全组 ID
```

**Aurora 安全组**（入站规则）:
```
Type: MySQL/Aurora
Protocol: TCP
Port: 3306
Source: EC2 安全组 ID
```

#### 2.3 SSH 登录到 EC2
```bash
ssh -i your-key.pem ec2-user@your-ec2-ip
```

#### 2.4 在 EC2 上安装 Java（如果未安装）
```bash
# Amazon Linux 2023
sudo yum install java-17-amazon-corretto -y

# Ubuntu
sudo apt update
sudo apt install openjdk-17-jdk -y

# 验证安装
java -version
```

---

### 步骤 3: 本地编译打包

在您的本地 Windows 机器上：

```powershell
# 进入项目目录
cd C:\Users\lin.rong\druid-long-query

# 清理并打包（跳过测试）
mvn clean package -DskipTests

# 验证 JAR 文件生成
ls target\druid-long-query-1.0.0-jar-with-dependencies.jar
```

---

### 步骤 4: 上传 JAR 到 EC2

#### 方法 1: 使用 SCP（推荐）
```powershell
# Windows PowerShell
scp -i C:\path\to\your-key.pem target\druid-long-query-1.0.0-jar-with-dependencies.jar ec2-user@your-ec2-ip:/home/ec2-user/
```

#### 方法 2: 使用 S3（适合频繁部署）
```powershell
# 上传到 S3
aws s3 cp target\druid-long-query-1.0.0-jar-with-dependencies.jar s3://your-bucket/

# 在 EC2 上下载
aws s3 cp s3://your-bucket/druid-long-query-1.0.0-jar-with-dependencies.jar .
```

---

### 步骤 5: 在 EC2 上配置和运行

#### 5.1 创建启动脚本

SSH 到 EC2 后，创建启动脚本：

```bash
# 创建脚本文件
cat > run-druid-test.sh << 'EOF'
#!/bin/bash

# Aurora 连接配置
export DB_URL="jdbc:mysql://your-aurora-cluster.cluster-xxxxx.ap-southeast-2.rds.amazonaws.com:3306/your_database?useSSL=false&serverTimezone=UTC"
export DB_USERNAME="admin"
export DB_PASSWORD="your_password"

# AWS 环境信息（可选）
export AWS_REGION="ap-southeast-2"
export EC2_INSTANCE_ID=$(ec2-metadata --instance-id | cut -d " " -f 2)

# 运行 JAR 并保存日志
java -jar druid-long-query-1.0.0-jar-with-dependencies.jar 2>&1 | tee druid-test-$(date +%Y%m%d-%H%M%S).log

EOF

# 赋予执行权限
chmod +x run-druid-test.sh
```

#### 5.2 修改脚本配置

**编辑脚本，替换实际值**：
```bash
nano run-druid-test.sh
```

修改以下内容：
- `your-aurora-cluster.cluster-xxxxx` → 您的 Aurora Writer Endpoint
- `your_database` → 您的数据库名
- `admin` → 您的用户名
- `your_password` → 您的密码
- `ap-southeast-2` → 您的 AWS 区域

保存：`Ctrl + X`，然后 `Y`，然后 `Enter`

#### 5.3 修改 SQL 查询

如果需要修改查询的表和条件，在本地修改代码：

在 `DruidLongQueryExample.java` 第 356 行左右：
```java
String sql = "SELECT * FROM your_actual_table WHERE id > 100 LIMIT 1000";
```

修改后重新编译打包并上传。

#### 5.4 运行测试

```bash
# 直接运行
./run-druid-test.sh

# 或后台运行
nohup ./run-druid-test.sh > output.log 2>&1 &
```

---

### 步骤 6: 查看日志

#### 实时查看日志
```bash
# 实时跟踪最新日志
tail -f druid-test-*.log

# 查看最后 100 行
tail -n 100 druid-test-*.log

# 查看全部日志
cat druid-test-*.log
```

#### 过滤关键信息
```bash
# 只看错误
grep -i "error\|exception\|failed" druid-test-*.log

# 查看连接池状态
grep "Connection Pool Status" druid-test-*.log -A 8

# 查看查询结果
grep "Query Completed\|Total records\|Total time" druid-test-*.log
```

#### 下载日志到本地
```powershell
# Windows PowerShell
scp -i C:\path\to\your-key.pem ec2-user@your-ec2-ip:/home/ec2-user/druid-test-*.log .
```

---

### 步骤 7: 使用 CloudWatch Logs（可选，适合长期运行）

#### 7.1 安装 CloudWatch Agent

```bash
# 下载 CloudWatch Agent
wget https://s3.amazonaws.com/amazoncloudwatch-agent/amazon_linux/amd64/latest/amazon-cloudwatch-agent.rpm

# 安装
sudo rpm -U ./amazon-cloudwatch-agent.rpm
```

#### 7.2 配置 CloudWatch Agent

```bash
# 创建配置文件
sudo cat > /opt/aws/amazon-cloudwatch-agent/etc/config.json << 'EOF'
{
  "logs": {
    "logs_collected": {
      "files": {
        "collect_list": [
          {
            "file_path": "/home/ec2-user/druid-test-*.log",
            "log_group_name": "/aws/ec2/druid-test",
            "log_stream_name": "{instance_id}",
            "timezone": "UTC"
          }
        ]
      }
    }
  }
}
EOF

# 启动 CloudWatch Agent
sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl \
    -a fetch-config \
    -m ec2 \
    -s \
    -c file:/opt/aws/amazon-cloudwatch-agent/etc/config.json
```

#### 7.3 在 AWS Console 查看日志

1. 打开 **CloudWatch** → **Logs** → **Log groups**
2. 找到 `/aws/ec2/druid-test`
3. 查看日志流

---

## 🔍 测试连接

### 快速测试 Aurora 连通性

在运行完整测试前，先验证连接：

```bash
# 使用 MySQL 客户端测试（需要先安装）
sudo yum install mysql -y

mysql -h your-aurora-cluster.cluster-xxxxx.rds.amazonaws.com \
      -u admin \
      -p \
      -D your_database

# 输入密码后，执行简单查询
mysql> SELECT 1;
mysql> SHOW TABLES;
mysql> exit
```

---

## 📊 预期输出示例

成功运行后，您应该看到类似的输出：

```
[2026-01-21 10:30:45] ========================================
[2026-01-21 10:30:45]   Druid Database Disconnection Testing Tool
[2026-01-21 10:30:45]   Environment: AWS
[2026-01-21 10:30:45] ========================================

[2026-01-21 10:30:45] AWS Region: ap-southeast-2
[2026-01-21 10:30:45] EC2 Instance ID: i-0123456789abcdef0
[2026-01-21 10:30:46] Druid connection pool initialized successfully
[2026-01-21 10:30:46] Database type: External database
[2026-01-21 10:30:46] Configuration mode: AWS production configuration
[2026-01-21 10:30:46] MaxActive: 120
[2026-01-21 10:30:46] TestOnBorrow: false
[2026-01-21 10:30:46] TestWhileIdle: true
[2026-01-21 10:30:46] KeepAlive: true

[2026-01-21 10:30:46] ===== Druid Connection Pool Status =====
[2026-01-21 10:30:46] Active connections: 0
[2026-01-21 10:30:46] Idle connections: 5
[2026-01-21 10:30:46] Total connections created: 5
...

[2026-01-21 10:30:47] ========== Starting Long Query ==========
[2026-01-21 10:30:47] Acquiring database connection...
[2026-01-21 10:30:47] ✓ Successfully acquired database connection, time taken: 45ms
[2026-01-21 10:30:47] Executing SQL: SELECT * FROM your_table WHERE id > 100 LIMIT 1000
[2026-01-21 10:30:47] Processed 1000 records, connection status: Active
[2026-01-21 10:30:47] ========== Query Completed Successfully ==========
[2026-01-21 10:30:47] Total records processed: 1000
[2026-01-21 10:30:47] Total time: 0.156 seconds
```

---

## 🐛 常见问题排查

### 1. 连接超时 (Connection timeout)

**症状**: `Communications link failure` 或 `Connection timed out`

**解决方案**:
- ✅ 检查安全组配置（步骤 2.2）
- ✅ 确认 Aurora 端点地址正确
- ✅ 检查 VPC 和子网设置
- ✅ 确认 EC2 和 Aurora 在同一个 VPC

### 2. 认证失败 (Access denied)

**症状**: `Access denied for user 'admin'@'...'`

**解决方案**:
- ✅ 检查用户名和密码是否正确
- ✅ 确认用户有访问该数据库的权限
- ✅ 检查 Aurora 是否允许该 IP 访问

### 3. 表不存在 (Table doesn't exist)

**症状**: `Table 'your_table_name' doesn't exist`

**解决方案**:
- ✅ 修改 SQL 为您实际的表名
- ✅ 使用 MySQL 客户端确认表名
- ✅ 检查大小写（MySQL 表名区分大小写）

### 4. JAR 文件找不到

**症状**: `Error: Unable to access jarfile`

**解决方案**:
```bash
# 检查文件是否存在
ls -lh druid-long-query-1.0.0-jar-with-dependencies.jar

# 检查当前目录
pwd

# 使用绝对路径
java -jar /home/ec2-user/druid-long-query-1.0.0-jar-with-dependencies.jar
```

---

## 🔄 持续使用

### 自动化脚本（可选）

创建定期执行脚本：

```bash
# 创建定时任务
cat > /home/ec2-user/crontab-druid << 'EOF'
# 每小时执行一次
0 * * * * /home/ec2-user/run-druid-test.sh

# 或每天早上 9 点执行
0 9 * * * /home/ec2-user/run-druid-test.sh
EOF

# 安装定时任务
crontab /home/ec2-user/crontab-druid

# 查看定时任务
crontab -l
```

---

## 📝 总结

**完整流程**：
1. ✅ 获取 Aurora 连接信息
2. ✅ 准备 EC2 实例并配置安全组
3. ✅ 本地编译打包 JAR
4. ✅ 上传 JAR 到 EC2
5. ✅ 配置环境变量和启动脚本
6. ✅ 运行测试并查看日志

**关键配置**：
- Aurora Endpoint
- 数据库用户名/密码
- 安全组规则
- 实际的 SQL 查询

**日志位置**：
- EC2: `/home/ec2-user/druid-test-*.log`
- CloudWatch: `/aws/ec2/druid-test` (如果配置)

---

## 📞 需要帮助？

如果遇到问题，请检查：
1. EC2 到 Aurora 的网络连通性
2. 安全组配置是否正确
3. 数据库凭证是否有效
4. JAR 文件是否完整上传
5. Java 版本是否兼容（需要 Java 8+）
