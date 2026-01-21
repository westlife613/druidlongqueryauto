# AWS控制台部署 - 快速开始指南

## 🚀 5分钟快速部署

按照以下步骤，通过AWS网页控制台快速部署。

---

## 第一步：本地准备（2分钟）

### 1. 打包JAR文件

在本地电脑上打开命令提示符，执行：

```cmd
cd C:\Users\lin.rong\druid-long-query
mvn clean package
```

### 2. 确认文件

打包完成后，检查文件是否存在：
```
C:\Users\lin.rong\druid-long-query\target\druid-long-query-1.0.0-jar-with-dependencies.jar
```

文件大小应该在 10-20 MB左右。

---

## 第二步：AWS控制台创建EC2（1分钟）

### 1. 登录AWS
- 访问：https://console.aws.amazon.com/
- 选择区域（右上角）：与你的RDS相同区域

### 2. 启动EC2实例

进入EC2服务 → 点击 **"启动实例"**

快速配置：
- **名称**: `druid-test`
- **AMI**: Amazon Linux 2023（或 Amazon Linux 2）
- **实例类型**: t3.small（或 t2.micro 免费套餐）
- **密钥对**: 
  - 新用户：点击"创建新密钥对"，名称填 `druid-key`，**下载并保存.pem文件**
  - 已有密钥：选择现有密钥
- **网络设置**: 
  - ✅ 允许来自以下位置的 SSH 流量：我的IP
  - ✅ 允许来自互联网的 HTTPS 流量（可选）

点击 **"启动实例"** → 等待状态变为 "正在运行"

---

## 第三步：配置安全组（30秒）

### 允许EC2访问RDS

1. 记下EC2的安全组ID（如 `sg-xxx123`）
2. 进入 **RDS控制台** → 选择数据库 → **修改**
3. 在"连接"部分 → **附加的VPC安全组** → 添加EC2的安全组
4. 或者修改RDS安全组，添加入站规则：
   - 类型：MySQL/Aurora
   - 源：EC2的安全组ID

---

## 第四步：上传文件到S3（1分钟）

### 1. 创建S3存储桶

进入 **S3控制台** → **创建存储桶**
- 存储桶名称：`druid-test-bucket-[随机数字]`（如 `druid-test-bucket-2026`）
- 区域：与EC2相同
- 其他默认 → **创建存储桶**

### 2. 上传文件

点击存储桶名称 → **上传** → **添加文件**

选择并上传：
- `druid-long-query-1.0.0-jar-with-dependencies.jar`（从 target 目录）
- `quick-start-ec2.sh`（项目根目录）

点击 **上传** → 等待完成

---

## 第五步：运行程序（1分钟）

### 1. 连接到EC2

在EC2控制台 → 选中实例 → 点击 **"连接"** → 选择 **"EC2 Instance Connect"** → **连接**

浏览器会打开一个黑色终端窗口。

### 2. 配置IAM角色（首次需要）

如果没有配置IAM角色，需要先配置：

**返回EC2控制台** → 选中实例 → **操作** → **安全** → **修改IAM角色**

如果没有角色，需要创建：
1. 点击"创建新IAM角色"
2. 受信任实体：AWS服务 - EC2
3. 权限策略：搜索并选择 `AmazonS3ReadOnlyAccess`
4. 角色名称：`EC2-S3-Access`
5. 创建并附加到实例

**等待1-2分钟**让角色生效。

### 3. 在终端执行以下命令

```bash
# 下载快速启动脚本
aws s3 cp s3://druid-test-bucket-2026/quick-start-ec2.sh ./
chmod +x quick-start-ec2.sh

# 编辑脚本，修改S3存储桶名称
nano quick-start-ec2.sh
# 或使用 vi: vi quick-start-ec2.sh
```

修改这一行：
```bash
S3_BUCKET="druid-test-bucket-2026"  # 改为你的存储桶名称
```

保存文件（nano: Ctrl+X, Y, Enter；vi: ESC, :wq, Enter）

```bash
# 运行脚本
./quick-start-ec2.sh
```

脚本会：
1. ✅ 自动安装Java
2. ✅ 从S3下载JAR文件
3. ✅ 询问运行模式（前台/后台）
4. ✅ 启动程序

### 4. 选择运行模式

```
请选择运行模式：
1) 前台运行（可以看到实时输出，Ctrl+C停止）
2) 后台运行（输出保存到 output.log）

请输入选项 [1/2]:
```

- **选择 1**：实时查看输出，适合短时间测试
- **选择 2**：后台运行，适合长时间测试

---

## 查看结果

### 如果选择了后台运行

```bash
# 查看实时日志
tail -f output.log

# 查看最后100行
tail -n 100 output.log

# 搜索错误
grep -i "error\|exception\|断连" output.log

# 查看进程
ps aux | grep java

# 停止程序
pkill -f druid-long-query
```

### 下载日志到本地

方式1：通过S3
```bash
# 上传到S3
aws s3 cp output.log s3://druid-test-bucket-2026/logs/output-$(date +%Y%m%d-%H%M%S).log

# 在S3控制台下载
```

方式2：在EC2 Instance Connect终端直接复制内容
```bash
cat output.log
```

---

## 手动运行（不使用脚本）

如果不想使用快速启动脚本，可以手动执行：

```bash
# 1. 安装Java
sudo yum install -y java-1.8.0-amazon-corretto-devel

# 2. 从S3下载JAR
aws s3 cp s3://druid-test-bucket-2026/druid-long-query-1.0.0-jar-with-dependencies.jar ./app.jar

# 3. 运行
java -jar app.jar

# 或后台运行
nohup java -jar app.jar > output.log 2>&1 &
```

---

## 常见问题

### ❌ 下载S3文件失败

**错误**: `Unable to locate credentials`

**解决**: 为EC2添加IAM角色（见"第五步 - 2. 配置IAM角色"）

### ❌ 连接RDS失败

**错误**: `Communications link failure`

**检查**:
1. EC2和RDS在同一VPC吗？
2. RDS安全组允许EC2访问吗？
3. 代码中的RDS终端节点正确吗？

**测试连接**:
```bash
# 安装MySQL客户端
sudo yum install -y mysql

# 测试连接（替换为你的RDS终端节点）
mysql -h your-rds-endpoint.us-east-1.rds.amazonaws.com -u admin -p
```

### ❌ Java未安装

```bash
sudo yum install -y java-1.8.0-amazon-corretto-devel
```

### ❌ 程序立即退出

查看完整错误：
```bash
java -jar app.jar 2>&1 | tee error.log
cat error.log
```

---

## 清理资源

测试完成后，避免产生费用：

1. **停止EC2实例**
   - EC2控制台 → 选中实例 → 实例状态 → 停止实例

2. **删除S3文件**（可选）
   - S3控制台 → 选择文件 → 删除

3. **终止EC2实例**（如果不再需要）
   - 实例状态 → 终止实例

---

## 成本参考

- **EC2 t2.micro**: 免费套餐（750小时/月）
- **EC2 t3.small**: ~$0.02/小时
- **S3存储**: ~$0.023/GB/月
- **数据传输**: EC2到RDS（同区域）免费

**建议**: 测试完成后立即停止实例

---

## 下一步

1. 观察日志中是否出现断连错误
2. 尝试不同的测试场景（修改代码中的 main 方法）
3. 调整Druid配置参数
4. 记录复现步骤和环境信息

---

## 需要帮助？

提供以下信息：
- EC2实例ID
- 完整的错误日志
- RDS终端节点
- 使用的测试SQL

完整文档参考：`AWS-CONSOLE-DEPLOYMENT.md`
