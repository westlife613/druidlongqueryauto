# AWS Elastic Beanstalk + RDS 部署指南

## 概述

本指南将教你使用 **Elastic Beanstalk** 部署 Druid 测试工具，比直接使用 EC2 简单 70%。

**优势：**
- ✅ 只需上传 JAR 包，无需配置服务器
- ✅ 自动管理服务器、负载均衡、监控
- ✅ 可视化界面操作
- ✅ 自动扩缩容

---

## 第一步：创建 RDS 数据库（15分钟）

### 1. 登录 AWS 控制台

访问：https://console.aws.amazon.com/
选择区域：建议 `us-east-1` 或 `ap-southeast-2`

### 2. 创建 RDS 数据库

1. **进入 RDS 服务**
   - 搜索框输入 "RDS"
   - 点击 "RDS" 服务

2. **创建数据库**
   - 点击橙色按钮 "创建数据库"
   
3. **选择创建方式**
   - 选择：**标准创建**

4. **引擎选项**
   - 引擎类型：**MySQL**
   - 版本：**MySQL 8.0.35**（或最新版本）

5. **模板**
   - 选择：**免费套餐**（测试用）
   - 或：**开发/测试**（生产环境）

6. **设置**
   - 数据库实例标识符：`druid-test-db`
   - 主用户名：`admin`
   - 主密码：设置一个强密码（记录下来！）
   - 确认密码：再次输入密码

7. **实例配置**
   - 免费套餐：自动选择 `db.t3.micro`
   - 开发环境：推荐 `db.t3.small`

8. **存储**
   - 存储类型：**通用型 SSD (gp3)**
   - 分配的存储：**20 GiB**
   - 取消勾选 "启用存储自动扩展"（测试环境）

9. **可用性和持久性**
   - 不创建备用实例（测试环境）

10. **连接**
    - VPC：默认 VPC
    - 公开访问：**是**（重要！这样 Elastic Beanstalk 可以连接）
    - VPC 安全组：
      - 选择 "创建新的"
      - 名称：`druid-test-db-sg`

11. **数据库身份验证**
    - 选择：**密码身份验证**

12. **其他配置**
    - 初始数据库名称：`testdb`（重要！）
    - 备份保留期：0天（测试环境）
    - 取消勾选 "启用加密"（简化测试）
    - 取消勾选 "启用增强监控"（节省费用）
    - 取消勾选 "启用自动次要版本升级"

13. **创建数据库**
    - 点击橙色按钮 "创建数据库"
    - 等待 5-10 分钟，状态变为 "可用"

### 3. 配置安全组（重要！）

等数据库创建完成后：

1. 点击数据库名称 `druid-test-db`
2. 找到 "连接性与安全性" 标签
3. 点击安全组链接（如 `druid-test-db-sg`）
4. 点击 "入站规则" 标签
5. 点击 "编辑入站规则"
6. 添加规则：
   - 类型：**MySQL/Aurora**
   - 协议：TCP
   - 端口：3306
   - 源：**0.0.0.0/0**（测试环境，生产环境应限制）
7. 保存规则

### 4. 记录数据库连接信息

在 RDS 数据库详情页，记录：

```
终端节点: druid-test-db.xxxxxx.us-east-1.rds.amazonaws.com
端口: 3306
数据库名: testdb
用户名: admin
密码: 你设置的密码
```

---

## 第二步：准备应用配置（5分钟）

### 1. 修改数据库配置

编辑 `src/main/java/DruidLongQueryExample.java`：

```java
// 替换为你的 RDS 连接信息
private static final String DB_URL = "jdbc:mysql://druid-test-db.xxxxxx.us-east-1.rds.amazonaws.com:3306/testdb?useUnicode=true&characterEncoding=utf8&useSSL=true&serverTimezone=UTC";
private static final String DB_USERNAME = "admin";
private static final String DB_PASSWORD = "你的RDS密码";
```

### 2. 创建测试表（可选）

如果需要测试数据，可以先用 MySQL 客户端连接 RDS，创建测试表：

```sql
CREATE TABLE test_table (
    id INT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100),
    amount DECIMAL(10,2),
    created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 插入测试数据
INSERT INTO test_table (name, amount) 
SELECT 
    CONCAT('Test-', n), 
    ROUND(RAND() * 1000, 2)
FROM (
    SELECT @row := @row + 1 as n FROM 
    (SELECT 0 UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3) t1,
    (SELECT 0 UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3) t2,
    (SELECT 0 UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3) t3,
    (SELECT @row:=0) t4
) numbers
LIMIT 10000;
```

### 3. 重新打包

```bash
mvn clean package
```

JAR 文件位置：
```
target/druid-long-query-1.0.0-jar-with-dependencies.jar
```

---

## 第三步：部署到 Elastic Beanstalk（10分钟）

### 1. 创建 Elastic Beanstalk 应用

1. **进入 Elastic Beanstalk 服务**
   - AWS 控制台搜索 "Elastic Beanstalk"
   - 点击进入

2. **创建应用**
   - 点击 "创建应用程序"

3. **配置应用程序**
   - 应用程序名称：`druid-test-app`
   - 标签（可选）：可以跳过

4. **配置环境**
   - 环境层：选择 **Web 服务器环境**

5. **平台**
   - 平台：**Java**
   - 平台分支：**Corretto 17** 或 **Java 8**
   - 平台版本：推荐版本（默认）

6. **应用程序代码**
   - 选择 **上传您的代码**
   - 版本标签：`v1.0`
   - 选择文件：上传 `druid-long-query-1.0.0-jar-with-dependencies.jar`

7. **预设**
   - 选择：**单实例（免费套餐）**

8. **下一步：配置更多选项**（可选）
   
   如果需要自定义配置，点击 "配置更多选项"：
   
   - **软件** → 编辑
     - 环境属性（可选）：
       ```
       DB_URL = jdbc:mysql://your-rds-endpoint:3306/testdb
       DB_USERNAME = admin
       DB_PASSWORD = your_password
       ```
   
   - **容量** → 编辑
     - 实例类型：`t3.micro`（免费套餐）或 `t3.small`
   
   - **安全性** → 编辑
     - 虚拟机密钥对：选择或创建密钥对（如果需要 SSH）
   
9. **创建环境**
   - 点击 "提交"
   - 等待 5-10 分钟，环境创建完成

### 2. 监控部署进度

在 Elastic Beanstalk 控制台：
- 查看事件日志
- 等待健康状态变为 **绿色（OK）**

---

## 第四步：运行测试（5分钟）

### 1. 访问应用

Elastic Beanstalk 会自动为你创建一个 URL：
```
http://druid-test-app.us-east-1.elasticbeanstalk.com
```

**注意：** 本应用是命令行工具，不是 Web 应用，所以访问 URL 可能没有响应。

### 2. 查看日志

**方式1：通过控制台**
1. 在 Elastic Beanstalk 环境页面
2. 点击左侧 "日志"
3. 点击 "请求日志" → "完整日志"
4. 下载日志文件查看应用输出

**方式2：实时日志（推荐）**
1. 点击 "日志"
2. 选择 "最后 100 行"
3. 查看应用输出

### 3. SSH 到实例运行测试（可选）

如果配置了密钥对，可以 SSH 到 EC2 实例：

```bash
# 1. 在 Elastic Beanstalk 控制台找到 EC2 实例 ID
# 2. 在 EC2 控制台找到公有 IP
# 3. SSH 连接
ssh -i your-key.pem ec2-user@your-instance-ip

# 4. 运行应用
cd /var/app/current
sudo java -jar druid-long-query-1.0.0-jar-with-dependencies.jar
```

---

## 第五步：测试不同场景

### 修改测试场景

如果需要测试不同场景，修改 `DruidLongQueryExample.java` 的 main 方法：

```java
// 场景1: 单次查询
executeLongQuery(sql);

// 场景2: 长查询后空闲等待
testLongQueryWithIdle(sql, 600);  // 等待10分钟

// 场景3: 连续多次查询
testMultipleLongQueries(sql, 5, 60);  // 5次，每次间隔60秒
```

然后：
1. 重新打包：`mvn clean package`
2. 在 Elastic Beanstalk 上传新版本
3. 查看日志

---

## 费用估算

**免费套餐（12个月）：**
- RDS db.t3.micro: 750小时/月（免费）
- Elastic Beanstalk: 免费（只付 EC2 费用）
- EC2 t3.micro: 750小时/月（免费）

**超出免费套餐：**
- RDS db.t3.micro: ~$15/月
- EC2 t3.micro: ~$7.5/月
- 总计：~$22.5/月

**节省建议：**
- 测试完成后立即删除资源
- 使用按需实例，不使用时停止

---

## 清理资源

测试完成后，删除资源避免费用：

### 1. 删除 Elastic Beanstalk 环境
1. 进入 Elastic Beanstalk 控制台
2. 选择环境
3. 操作 → 终止环境

### 2. 删除 RDS 数据库
1. 进入 RDS 控制台
2. 选择数据库 `druid-test-db`
3. 操作 → 删除
4. 取消勾选 "创建最终快照"
5. 确认删除

---

## 故障排查

### 问题1: 应用无法连接数据库

**解决：**
1. 检查 RDS 安全组是否允许 3306 端口
2. 检查 RDS 公开访问是否设置为 "是"
3. 检查数据库连接字符串是否正确
4. 在 Elastic Beanstalk 日志中查看详细错误

### 问题2: Elastic Beanstalk 健康状态为红色

**解决：**
1. 查看事件日志，找到错误信息
2. 常见原因：
   - JAR 包上传失败
   - Java 版本不匹配
   - 应用启动失败

### 问题3: 找不到主类

**解决：**
检查 `pom.xml` 中是否正确配置了 mainClass：
```xml
<manifest>
    <mainClass>DruidLongQueryExample</mainClass>
</manifest>
```

---

## 其他部署选项对比

| 选项 | 复杂度 | 适合场景 | 费用 |
|------|--------|---------|------|
| **Elastic Beanstalk** | ⭐⭐ | 推荐！自动化部署 | 中 |
| EC2 手动部署 | ⭐⭐⭐⭐⭐ | 完全控制 | 中 |
| ECS Fargate | ⭐⭐⭐ | 容器化应用 | 中高 |
| Lambda | ❌ | 不适合（长查询超时） | 低 |
| Lightsail | ⭐⭐⭐ | 简单固定费用 | 低 |

---

## 下一步

1. **测试完成**：查看日志，分析 Druid 连接池行为
2. **调整参数**：修改 Druid 配置，重新部署测试
3. **生产部署**：根据测试结果，配置最优参数用于生产环境

有问题查看 Elastic Beanstalk 日志或 RDS 性能监控。
