# AWS控制台部署指南（网页端操作）

## 概述

本指南将指导你通过AWS管理控制台（网页）部署Druid数据库断连测试工具，**无需使用命令行工具**。

---

## 第一部分：准备工作

### 步骤1: 本地打包JAR文件

在本地Windows电脑上：

1. 打开命令提示符（CMD）或PowerShell
2. 进入项目目录：
   ```cmd
   cd C:\Users\lin.rong\druid-long-query
   ```
3. 运行打包命令：
   ```cmd
   mvn clean package
   ```
4. 打包完成后，JAR文件位于：
   ```
   C:\Users\lin.rong\druid-long-query\target\druid-long-query-1.0.0-jar-with-dependencies.jar
   ```

### 步骤2: 准备文件上传

将以下文件复制到一个文件夹（如桌面上的 `druid-upload` 文件夹）：
- `druid-long-query-1.0.0-jar-with-dependencies.jar`（从target目录复制）
- `run-on-ec2.sh`（项目根目录）

---

## 第二部分：创建EC2实例

### 步骤1: 登录AWS控制台

1. 打开浏览器，访问：https://console.aws.amazon.com/
2. 输入你的AWS账号和密码登录
3. 在右上角选择你要使用的区域（如 `us-east-1` 美国东部）

### 步骤2: 创建EC2实例

1. **进入EC2服务**
   - 在控制台搜索框输入 "EC2"
   - 点击 "EC2" 服务

2. **启动实例**
   - 点击橙色按钮 "启动实例" (Launch Instance)

3. **配置实例名称和标签**
   - 名称：`druid-test-instance`

4. **选择AMI（操作系统镜像）**
   - 选择 "Amazon Linux 2 AMI (HVM)"
   - 或 "Amazon Linux 2023"
   - 架构：64位 (x86)

5. **选择实例类型**
   - 推荐：`t3.small` (2 vCPU, 2GB RAM)
   - 或免费套餐：`t2.micro` (1 vCPU, 1GB RAM)

6. **密钥对（重要）**
   - 如果没有密钥对：
     - 点击 "创建新密钥对"
     - 密钥对名称：`druid-test-key`
     - 密钥对类型：RSA
     - 私有密钥文件格式：.pem（Linux/Mac）或 .ppk（Windows PuTTY）
     - 点击 "创建密钥对"
     - **下载的密钥文件妥善保存，只会下载一次！**
   - 如果已有密钥对：选择现有密钥对

7. **网络设置**
   - VPC：选择默认VPC（或你RDS所在的VPC）
   - 子网：选择任意公有子网
   - 自动分配公有IP：**启用**
   
   创建安全组：
   - 安全组名称：`druid-test-sg`
   - 描述：Security group for Druid test
   - 
   入站安全组规则：
   - 规则1：
     - 类型：SSH
     - 协议：TCP
     - 端口：22
     - 源类型：我的IP（推荐）或 任何位置 0.0.0.0/0
   
   出站规则：默认允许所有流量

8. **配置存储**
   - 大小：8 GiB（默认）
   - 卷类型：gp3（推荐）或 gp2

9. **高级详细信息（可选）**
   - 保持默认即可

10. **启动实例**
    - 点击右侧 "启动实例" 按钮
    - 等待实例状态变为 "正在运行" (Running)

### 步骤3: 配置安全组访问RDS

1. **获取RDS信息**
   - 在控制台搜索框输入 "RDS"
   - 点击你的数据库实例
   - 复制 "安全组" 名称（如 `rds-sg-xxx`）

2. **修改EC2安全组**
   - 返回EC2控制台
   - 左侧菜单：网络与安全 → 安全组
   - 找到刚创建的 `druid-test-sg`
   - 点击安全组ID
   
3. **添加出站规则**
   - 点击 "出站规则" 标签
   - 点击 "编辑出站规则"
   - 点击 "添加规则"
   - 类型：MySQL/Aurora (端口 3306)
   - 目标：选择RDS的安全组
   - 保存规则

4. **修改RDS安全组**
   - 找到RDS的安全组（`rds-sg-xxx`）
   - 点击 "入站规则" 标签
   - 点击 "编辑入站规则"
   - 点击 "添加规则"
   - 类型：MySQL/Aurora (端口 3306)
   - 源：选择EC2的安全组 `druid-test-sg`
   - 保存规则

---

## 第三部分：使用S3上传文件

### 步骤1: 创建S3存储桶

1. **进入S3服务**
   - 在控制台搜索 "S3"
   - 点击 "S3" 服务

2. **创建存储桶**
   - 点击 "创建存储桶"
   - 存储桶名称：`druid-test-upload-[你的名字]`（必须全球唯一）
   - 区域：与EC2相同的区域
   - 其他设置保持默认
   - 点击 "创建存储桶"

### 步骤2: 上传文件到S3

1. **进入存储桶**
   - 点击刚创建的存储桶名称

2. **上传文件**
   - 点击 "上传" 按钮
   - 点击 "添加文件"
   - 选择以下文件：
     - `druid-long-query-1.0.0-jar-with-dependencies.jar`
     - `run-on-ec2.sh`
   - 点击 "上传"
   - 等待上传完成

3. **获取文件URL**
   - 点击 JAR 文件名
   - 复制 "对象URL" 或记下存储桶名称和文件名

---

## 第四部分：连接EC2并运行程序

### 方式A: 使用EC2 Instance Connect（最简单）

1. **连接到实例**
   - 返回EC2控制台
   - 选中你的实例
   - 点击顶部 "连接" 按钮
   - 选择 "EC2 Instance Connect" 标签
   - 用户名：`ec2-user`
   - 点击 "连接"
   - 浏览器会打开一个终端窗口

2. **在终端执行以下命令**

```bash
# 1. 安装Java（如果未安装）
sudo yum install -y java-1.8.0-amazon-corretto-devel

# 2. 验证Java安装
java -version

# 3. 从S3下载文件
aws s3 cp s3://druid-test-upload-[你的名字]/druid-long-query-1.0.0-jar-with-dependencies.jar ./app.jar
aws s3 cp s3://druid-test-upload-[你的名字]/run-on-ec2.sh ./

# 4. 设置执行权限
chmod +x run-on-ec2.sh

# 5. 运行程序
./run-on-ec2.sh
```

如果需要直接运行JAR：
```bash
java -jar app.jar
```

### 方式B: 使用Session Manager（推荐，无需SSH密钥）

1. **配置IAM角色**
   - 在EC2控制台，选中实例
   - 操作 → 安全 → 修改IAM角色
   - 选择或创建包含 `AmazonSSMManagedInstanceCore` 策略的角色
   - 保存

2. **等待2-3分钟** 让SSM Agent注册

3. **连接**
   - 选中实例
   - 点击 "连接"
   - 选择 "Session Manager" 标签
   - 点击 "连接"

4. **执行命令**（同方式A）

### 方式C: 使用PuTTY（Windows用户）

1. **下载PuTTY**
   - 访问：https://www.putty.org/
   - 下载并安装PuTTY

2. **转换密钥（如果是.pem格式）**
   - 打开 PuTTYgen
   - Load → 选择你的 .pem 文件
   - Save private key → 保存为 .ppk 文件

3. **连接EC2**
   - 打开PuTTY
   - Host Name：`ec2-user@[EC2公有IP]`
   - Port：22
   - Connection → SSH → Auth → Browse → 选择 .ppk 文件
   - 点击 "Open"

4. **执行命令**（同方式A）

---

## 第五部分：运行测试

### 选项1: 使用一键运行脚本

如果使用了 `run-on-ec2.sh`：

```bash
./run-on-ec2.sh
```

### 选项2: 手动运行

```bash
# 直接运行
java -jar app.jar

# 后台运行并保存日志
nohup java -jar app.jar > output.log 2>&1 &

# 查看日志
tail -f output.log

# 查看进程
ps aux | grep java

# 停止程序
pkill -f druid-long-query
```

---

## 第六部分：查看结果

### 实时查看输出

```bash
# 如果使用nohup后台运行
tail -f output.log

# 筛选错误信息
grep -i "error\|exception\|断连" output.log

# 查看最后100行
tail -n 100 output.log
```

### 下载日志文件

1. **上传到S3**
   ```bash
   aws s3 cp output.log s3://druid-test-upload-[你的名字]/logs/
   ```

2. **在S3控制台下载**
   - 进入S3存储桶
   - 进入 `logs` 文件夹
   - 选中 `output.log`
   - 点击 "下载"

---

## 第七部分：清理资源（完成测试后）

### 步骤1: 停止或终止EC2实例

1. 进入EC2控制台
2. 选中实例
3. 实例状态 → **停止实例**（保留实例以后使用）
   - 或 **终止实例**（完全删除）

### 步骤2: 删除S3文件（可选）

1. 进入S3控制台
2. 选中存储桶
3. 选中要删除的文件
4. 点击 "删除"

### 步骤3: 删除安全组（可选）

1. 进入EC2 → 安全组
2. 选中 `druid-test-sg`
3. 操作 → 删除安全组

---

## 常见问题

### 1. 无法连接EC2

**检查清单:**
- ✅ 实例状态是 "正在运行"
- ✅ 安全组允许SSH（端口22）
- ✅ 实例有公有IP地址
- ✅ 使用正确的密钥对

### 2. 无法下载S3文件

**错误:** `Unable to locate credentials`

**解决方法:**

为EC2实例添加IAM角色：
1. EC2控制台 → 选中实例
2. 操作 → 安全 → 修改IAM角色
3. 创建新角色或选择包含 S3 读取权限的角色
4. 保存

创建IAM角色：
1. IAM控制台 → 角色 → 创建角色
2. 受信任实体类型：AWS服务
3. 用例：EC2
4. 添加权限策略：`AmazonS3ReadOnlyAccess`
5. 角色名称：`EC2-S3-Read`
6. 创建角色

### 3. Java未安装

```bash
# Amazon Linux 2
sudo yum install -y java-1.8.0-amazon-corretto-devel

# Ubuntu
sudo apt update
sudo apt install -y openjdk-8-jdk
```

### 4. 连接RDS失败

**检查清单:**
- ✅ EC2和RDS在同一VPC
- ✅ RDS安全组允许EC2安全组访问
- ✅ RDS终端节点正确
- ✅ 数据库用户名密码正确

**测试连接:**
```bash
# 安装MySQL客户端
sudo yum install -y mysql

# 测试连接
mysql -h your-rds-endpoint -u admin -p
```

### 5. 程序运行后立即退出

查看完整错误信息：
```bash
java -jar app.jar 2>&1 | tee full-log.txt
```

---

## 快速命令参考

### EC2上的完整部署流程（复制粘贴）

```bash
# 安装Java
sudo yum install -y java-1.8.0-amazon-corretto-devel

# 验证安装
java -version

# 从S3下载（替换存储桶名称）
aws s3 cp s3://你的存储桶名称/druid-long-query-1.0.0-jar-with-dependencies.jar ./app.jar

# 运行
java -jar app.jar

# 或后台运行
nohup java -jar app.jar > output.log 2>&1 &

# 查看日志
tail -f output.log
```

---

## 成本估算

### 免费套餐（首次使用AWS）
- EC2 t2.micro：750小时/月（免费）
- S3：5GB存储（免费）

### 付费（超出免费套餐）
- EC2 t3.small：约 $0.02/小时 = $15/月
- S3存储：$0.023/GB/月
- 数据传输（EC2→RDS同区域）：免费

### 节省成本
- 测试完成后停止EC2实例
- 删除不需要的S3文件
- 使用Spot实例可节省70%

---

## 下一步

测试完成后：
1. 分析日志中的断连错误
2. 调整Druid配置重新测试
3. 记录复现步骤和环境信息
4. 如需防止断连，修改代码中的保活配置

---

## 技术支持

如遇问题，提供以下信息：
- EC2实例ID
- 完整错误日志
- RDS类型和版本
- 网络配置截图
