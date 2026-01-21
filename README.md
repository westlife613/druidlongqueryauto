# Druid数据库断连问题复现测试工具

## 功能说明

这是一个用于**复现和调试数据库断连问题**的测试工具，通过Druid连接池执行长查询来暴露断连问题。

## 与防断连脚本的区别

该脚本**不是**用来防止断连，而是：
- ✅ 复现生产环境的断连问题
- ✅ 调试和定位断连原因
- ✅ 测试不同场景下的连接行为
- ✅ 记录详细的断连日志和状态

## 关键配置说明

### 用于复现问题的配置

```java
keepAlive=false              // 关闭保活，让问题暴露
testWhileIdle=false          // 关闭空闲检测
testOnBorrow=true            // 获取连接时检测，可以发现断连
```

这些配置会让数据库的断连问题更容易复现。

## 内置测试场景

### 场景1: 单次长查询
最基础的测试，执行一次长查询。

```java
executeLongQuery(sql);
```

### 场景2: 长查询 + 空闲等待 + 再次查询
测试连接在长时间空闲后是否会被服务器断开（如MySQL的8小时超时）。

```java
testLongQueryWithIdle(sql, 600);  // 等待10分钟后再次查询
```

**用途**: 复现MySQL `wait_timeout` 或 `interactive_timeout` 导致的断连。

### 场景3: 连续多次长查询
测试连接复用和连接池管理。

```java
testMultipleLongQueries(sql, 5, 60);  // 执行5次，每次间隔60秒
```

**用途**: 测试连接池在多次使用后是否稳定。

### 场景4: 获取连接后等待再查询
获取连接后先等待一段时间，然后再执行查询。

```java
testConnectionIdleBeforeQuery(sql, 300);  // 获取连接后等待5分钟
```

**用途**: 测试连接获取后，在应用层业务处理期间，连接是否会超时。

## 使用步骤

### 1. 修改数据库配置

编辑 [DruidLongQueryExample.java](DruidLongQueryExample.java#L25-L28)：

```java
dataSource.setUrl("jdbc:mysql://your_host:3306/your_database");
dataSource.setUsername("your_username");
dataSource.setPassword("your_password");
```

### 2. 配置测试SQL

修改 [main方法](DruidLongQueryExample.java#L245)中的SQL语句：

```java
// 方式1: 简单查询
String sql = "SELECT * FROM your_table LIMIT 100";

// 方式2: 使用SLEEP模拟长查询（MySQL）
String sql = "SELECT SLEEP(30), * FROM your_table LIMIT 10";

// 方式3: 大数据量查询
String sql = "SELECT * FROM large_table WHERE date > '2024-01-01'";
```

### 3. 选择测试场景

在 main 方法中取消注释想要执行的场景：

```java
// 场景1: 单次查询（默认）
executeLongQuery(sql);

// 场景2: 测试空闲断连（取消注释）
// testLongQueryWithIdle(sql, 600);

// 场景3: 连续查询（取消注释）
// testMultipleLongQueries(sql, 5, 60);

// 场景4: 获取连接后等待（取消注释）  
// testConnectionIdleBeforeQuery(sql, 300);
```

### 4. 运行测试

```bash
cd C:\Users\lin.rong\druid-long-query
mvn clean package
java -jar target\druid-long-query-1.0.0-jar-with-dependencies.jar
```

## 输出日志说明

脚本会输出详细的带时间戳的日志：

```
[2026-01-20 14:30:00] ========== 开始执行长查询 ==========
[2026-01-20 14:30:00] 正在获取数据库连接...
[2026-01-20 14:30:01] ✓ 成功获取数据库连接，耗时: 234ms
[2026-01-20 14:30:01] 连接ID: 123456
[2026-01-20 14:30:01] 执行SQL: SELECT * FROM test_table
[2026-01-20 14:30:15] 查询成功完成
[2026-01-20 14:30:15] 共处理: 5000 条记录
```

### 断连错误示例

当检测到断连时，会显示：

```
[2026-01-20 14:35:00] !!!!! 发生SQL异常 !!!!!
[2026-01-20 14:35:00] 异常类型: com.mysql.cj.jdbc.exceptions.CommunicationsException
[2026-01-20 14:35:00] 错误代码: 0
[2026-01-20 14:35:00] 错误信息: Communications link failure
[2026-01-20 14:35:00] *** 检测到数据库断连问题！***
```

## 常见断连错误码

### MySQL
- **2006**: MySQL server has gone away（服务器断开连接）
- **2013**: Lost connection to MySQL server（丢失与服务器的连接）
- **0**: Communications link failure（通信链接失败）

### 可能的断连原因

1. **数据库超时配置**
   - MySQL: `wait_timeout`, `interactive_timeout`（默认8小时）
   - Oracle: `SQLNET.EXPIRE_TIME`
   - PostgreSQL: `idle_in_transaction_session_timeout`

2. **网络问题**
   - 防火墙关闭长时间空闲连接
   - 网络设备超时
   - NAT表项过期

3. **数据库服务器重启或维护**

4. **连接池配置不当**
   - 连接获取超时
   - 连接验证失败

## 调试建议

### 1. 查看数据库配置

**MySQL:**
```sql
SHOW VARIABLES LIKE '%timeout%';
SHOW VARIABLES LIKE 'max_connections';
```

**Oracle:**
```sql
SELECT name, value FROM v$parameter WHERE name LIKE '%timeout%';
```

### 2. 调整测试参数

- **增加空闲时间**: 从10分钟逐步增加到8小时以上
- **减小连接池大小**: 更容易观察单个连接的行为
- **启用TestOnBorrow**: 在获取连接时立即发现断连

### 3. 对比配置

创建两个版本：
- 版本A：关闭所有保活机制（当前配置）
- 版本B：开启保活机制（参考下方防断连配置）

对比两个版本的表现。

## 如何改为防断连模式

如果测试完成后需要防止断连，修改配置：

```java
dataSource.setKeepAlive(true);                    // 开启保活
dataSource.setKeepAliveBetweenTimeMillis(60000);  // 每60秒保活
dataSource.setTestWhileIdle(true);                // 空闲时检测
dataSource.setTestOnBorrow(false);                // 获取时不检测（性能优化）
```

## 不同数据库示例

### MySQL
```java
dataSource.setUrl("jdbc:mysql://localhost:3306/db?useSSL=false&serverTimezone=Asia/Shanghai");
dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
dataSource.setValidationQuery("SELECT 1");
```

### Oracle
```java
dataSource.setUrl("jdbc:oracle:thin:@localhost:1521:orcl");
dataSource.setDriverClassName("oracle.jdbc.OracleDriver");
dataSource.setValidationQuery("SELECT 1 FROM DUAL");
```

### PostgreSQL
```java
dataSource.setUrl("jdbc:postgresql://localhost:5432/db");
dataSource.setDriverClassName("org.postgresql.Driver");
dataSource.setValidationQuery("SELECT 1");
```

### SQL Server
```java
dataSource.setUrl("jdbc:sqlserver://localhost:1433;databaseName=db");
dataSource.setDriverClassName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
dataSource.setValidationQuery("SELECT 1");
```

## 注意事项

1. **生产环境谨慎使用**: 此脚本会关闭保活机制，可能导致连接断开
2. **监控资源使用**: 长查询可能占用大量内存和CPU
3. **合理设置等待时间**: 太长会浪费时间，太短可能无法复现问题
4. **记录完整日志**: 保存所有输出用于分析
5. **备份重要数据**: 测试前确保数据安全

## 依赖版本

- Druid: 1.2.20
- MySQL Connector: 8.0.33
- Java: 8+

## 许可证

MIT License
