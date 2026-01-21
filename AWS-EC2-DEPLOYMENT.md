# AWS EC2部署指南（无Docker版本）

## 概述

本指南说明如何在AWS EC2实例上直接运行Druid数据库断连测试工具，**不使用Docker**。

## 前提条件

### 本地环境
- Maven 3.x
- Java 8或更高版本
- SSH客户端（Windows可使用Git Bash、WSL或PuTTY）

### AWS环境
- AWS EC2实例（推荐Amazon Linux 2或Ubuntu）
- EC2实例的SSH密钥(.pem文件)
- EC2实例可以访问RDS数据库

## 快速开始

### 步骤1: 配置数据库连接

编辑 `DruidLongQueryExample.java`（第20-32行），修改为你的RDS信息：

```java
// RDS MySQL配置
private static final String DB_URL = "jdbc:mysql://your-rds-endpoint.us-east-1.rds.amazonaws.com:3306/your_database?useSSL=true&serverTimezone=Asia/Shanghai";
private static final String DB_USERNAME = "admin";
private static final String DB_PASSWORD = "your_password_here";
```

**获取RDS终端节点:**
1. AWS控制台 → RDS → 数据库
2. 选择你的数据库实例
3. 复制"终端节点"地址

**示例:**
```java
private static final String DB_URL = "jdbc:mysql://mydb.c1a2b3c4d5e6.us-east-1.rds.amazonaws.com:3306/testdb?useSSL=true";
private static final String DB_USERNAME = "admin";
private static final String DB_PASSWORD = "MyPassword123!";
```

### 步骤2: 本地测试（可选）

在部署到AWS之前，可以在本地测试：

**Windows:**
```cmd
run-local.bat
```

**Linux/Mac:**
```bash
chmod +x run-local.sh
./run-local.sh
```

### 步骤3: 部署到EC2

#### 方式A: 使用自动部署脚本

**Windows用户:**

1. 编辑 `deploy-to-ec2.bat`，修改配置：
```batch
set EC2_HOST=ec2-user@your-ec2-ip
set SSH_KEY=C:\path\to\your-key.pem
set REMOTE_DIR=/home/ec2-user/druid-test
```

2. 运行部署：
```cmd
deploy-to-ec2.bat
```

**Linux/Mac用户:**

1. 编辑 `deploy-to-ec2.sh`，修改配置：
```bash
EC2_HOST="ec2-user@your-ec2-ip"
SSH_KEY="path/to/your-key.pem"
REMOTE_DIR="/home/ec2-user/druid-test"
```

2. 运行部署：
```bash
chmod +x deploy-to-ec2.sh
./deploy-to-ec2.sh
```

#### 方式B: 手动部署

**1. 本地打包:**
```bash
mvn clean package
```

**2. 上传到EC2:**
```bash
# 使用SCP上传
scp -i your-key.pem target/druid-long-query-1.0.0-jar-with-dependencies.jar ec2-user@your-ec2-ip:~/

# 或使用SFTP
sftp -i your-key.pem ec2-user@your-ec2-ip
put target/druid-long-query-1.0.0-jar-with-dependencies.jar
```

**3. SSH登录EC2:**
```bash
ssh -i your-key.pem ec2-user@your-ec2-ip
```

**4. 在EC2上安装Java（如果尚未安装）:**

Amazon Linux 2:
```bash
sudo yum install -y java-1.8.0-amazon-corretto-devel
```

Ubuntu:
```bash
sudo apt update
sudo apt install -y openjdk-8-jdk
```

**5. 运行程序:**
```bash
java -jar druid-long-query-1.0.0-jar-with-dependencies.jar
```

## 不同数据库引擎配置

### RDS MySQL / Aurora MySQL

```java
private static final String DB_URL = "jdbc:mysql://endpoint.region.rds.amazonaws.com:3306/database?useSSL=true&serverTimezone=Asia/Shanghai";
private static final String DB_USERNAME = "admin";
private static final String DB_PASSWORD = "password";
```

修改驱动类（已在代码中）:
```java
dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
```

### RDS PostgreSQL / Aurora PostgreSQL

在 `pom.xml` 中取消注释PostgreSQL依赖，然后修改配置：

```java
private static final String DB_URL = "jdbc:postgresql://endpoint.region.rds.amazonaws.com:5432/database?ssl=true";
private static final String DB_USERNAME = "postgres";
private static final String DB_PASSWORD = "password";
```

修改代码中的驱动类：
```java
dataSource.setDriverClassName("org.postgresql.Driver");
dataSource.setValidationQuery("SELECT 1");
```

### Oracle RDS

在 `pom.xml` 中取消注释Oracle依赖，然后修改配置：

```java
private static final String DB_URL = "jdbc:oracle:thin:@endpoint.region.rds.amazonaws.com:1521:ORCL";
private static final String DB_USERNAME = "admin";
private static final String DB_PASSWORD = "password";
```

修改代码中的驱动类：
```java
dataSource.setDriverClassName("oracle.jdbc.OracleDriver");
dataSource.setValidationQuery("SELECT 1 FROM DUAL");
```

## 网络和安全配置

### 1. EC2安全组配置

**出站规则（Outbound）:**
| 类型 | 协议 | 端口 | 目标 |
|------|------|------|------|
| MySQL/Aurora | TCP | 3306 | RDS安全组ID |
| PostgreSQL | TCP | 5432 | RDS安全组ID |
| All traffic | All | All | 0.0.0.0/0 (测试用) |

### 2. RDS安全组配置

**入站规则（Inbound）:**
| 类型 | 协议 | 端口 | 源 |
|------|------|------|------|
| MySQL/Aurora | TCP | 3306 | EC2安全组ID |

**推荐配置:**
- 源类型：自定义
- 源：EC2实例的安全组ID（如 sg-xxxxx）

### 3. 测试网络连接

在EC2上测试与RDS的连接：

**测试端口连通性:**
```bash
# 测试MySQL
telnet your-rds-endpoint 3306

# 或使用nc
nc -zv your-rds-endpoint 3306

# 或使用nmap
nmap -p 3306 your-rds-endpoint
```

**使用MySQL客户端测试:**
```bash
# 安装MySQL客户端
sudo yum install -y mysql

# 连接测试
mysql -h your-rds-endpoint -u admin -p
```

## 测试场景配置

编辑 `DruidLongQueryExample.java` 的 main 方法（第260-280行），选择测试场景：

### 场景1: 单次长查询
```java
String sql = "SELECT * FROM your_table LIMIT 100";
executeLongQuery(sql);
```

### 场景2: 测试8小时超时断连
```java
String sql = "SELECT * FROM your_table LIMIT 100";
testLongQueryWithIdle(sql, 28800);  // 等待8小时
```

### 场景3: 连续查询测试连接复用
```java
String sql = "SELECT * FROM your_table LIMIT 100";
testMultipleLongQueries(sql, 10, 60);  // 10次，每次间隔60秒
```

### 场景4: 模拟慢查询（MySQL）
```java
String sql = "SELECT SLEEP(30), * FROM your_table LIMIT 10";
executeLongQuery(sql);
```

## 在EC2上后台运行

### 使用nohup后台运行
```bash
nohup java -jar druid-long-query-1.0.0-jar-with-dependencies.jar > output.log 2>&1 &
```

查看进程：
```bash
ps aux | grep druid-long-query
```

查看日志：
```bash
tail -f output.log
```

停止程序：
```bash
pkill -f druid-long-query
```

### 使用screen会话
```bash
# 安装screen
sudo yum install -y screen

# 创建新会话
screen -S druid-test

# 运行程序
java -jar druid-long-query-1.0.0-jar-with-dependencies.jar

# 按 Ctrl+A 然后按 D 分离会话

# 重新连接会话
screen -r druid-test

# 列出所有会话
screen -ls
```

### 创建systemd服务（持久化运行）

创建服务文件 `/etc/systemd/system/druid-test.service`:

```ini
[Unit]
Description=Druid Long Query Test
After=network.target

[Service]
Type=simple
User=ec2-user
WorkingDirectory=/home/ec2-user/druid-test
ExecStart=/usr/bin/java -jar app.jar
Restart=on-failure
RestartSec=10
StandardOutput=append:/home/ec2-user/druid-test/output.log
StandardError=append:/home/ec2-user/druid-test/error.log

[Install]
WantedBy=multi-user.target
```

启动服务：
```bash
sudo systemctl daemon-reload
sudo systemctl start druid-test
sudo systemctl enable druid-test  # 开机自启
sudo systemctl status druid-test
```

查看日志：
```bash
sudo journalctl -u druid-test -f
```

## 常见问题排查

### 1. 连接超时

**错误信息:**
```
Communications link failure
```

**检查清单:**
- ✅ EC2安全组允许访问RDS端口
- ✅ RDS安全组允许EC2访问
- ✅ EC2和RDS在同一VPC或正确配置了VPC Peering
- ✅ RDS终端节点正确

**测试命令:**
```bash
telnet your-rds-endpoint 3306
```

### 2. 认证失败

**错误信息:**
```
Access denied for user 'admin'@'ip-address'
```

**检查清单:**
- ✅ 用户名和密码正确
- ✅ RDS用户权限正确
- ✅ RDS未开启IAM认证（或正确配置）

**在RDS上检查用户:**
```sql
SELECT user, host FROM mysql.user;
SHOW GRANTS FOR 'admin'@'%';
```

### 3. Java版本问题

**错误信息:**
```
Unsupported major.minor version
```

**解决方法:**
```bash
# 检查Java版本
java -version

# 安装正确版本
sudo yum install -y java-1.8.0-amazon-corretto-devel
```

### 4. 内存不足

**错误信息:**
```
java.lang.OutOfMemoryError
```

**增加堆内存:**
```bash
java -Xms512m -Xmx2048m -jar app.jar
```

## 监控和日志

### 查看实时日志
```bash
# 程序输出
tail -f output.log

# 筛选错误
grep "ERROR\|Exception" output.log

# 查看断连相关日志
grep "断连\|Communications\|Lost connection" output.log
```

### CloudWatch集成

安装CloudWatch Agent:
```bash
sudo yum install -y amazon-cloudwatch-agent
```

配置日志推送到CloudWatch，然后在AWS控制台查看。

## 成本预估

### EC2实例（测试用）
- **t3.micro** (1 vCPU, 1GB RAM): ~$7.5/月
- **t3.small** (2 vCPU, 2GB RAM): ~$15/月

### 数据传输
- EC2到RDS（同可用区）: 免费
- EC2到RDS（同区域不同可用区）: $0.01/GB

### 建议
- 测试完成后停止EC2实例节省成本
- 使用Spot实例可节省70%成本

## 安全注意事项

⚠️ **当前配置将密码硬编码在代码中**

**这适用于:**
- ✅ 临时测试
- ✅ 开发环境
- ✅ 问题复现

**不适用于:**
- ❌ 生产环境
- ❌ 长期运行
- ❌ 共享代码

**生产环境建议:**
1. 使用AWS Secrets Manager
2. 使用环境变量
3. 使用配置文件（添加到.gitignore）
4. 使用IAM数据库认证

## 卸载和清理

```bash
# 停止程序
pkill -f druid-long-query

# 删除文件
rm -rf ~/druid-test

# 停止并禁用systemd服务（如果创建了）
sudo systemctl stop druid-test
sudo systemctl disable druid-test
sudo rm /etc/systemd/system/druid-test.service
sudo systemctl daemon-reload
```

## 下一步

测试完成后，如果需要防止断连，参考项目中的配置注释修改为保活模式。
