import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.DruidPooledConnection;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Druid连接池长查询测试脚本
 * 用于复现和调试数据库断连问题
 */
public class DruidLongQueryExample {

    private static DruidDataSource dataSource;
    // SimpleDateFormat不是线程安全的，但本程序是单线程运行，可以忽略警告
    @SuppressWarnings("SimpleDateFormat")
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    // ===== 数据库配置 =====
    // 直接配置数据库连接信息（请修改为您的实际值）
    private static final String DB_URL = System.getenv("DB_URL") != null 
        ? System.getenv("DB_URL") 
        : "jdbc:mysql://your-aurora-cluster.cluster-xxxxx.ap-southeast-2.rds.amazonaws.com:3306/your_database?useSSL=false&serverTimezone=UTC";
    
    private static final String DB_USERNAME = System.getenv("DB_USERNAME") != null 
        ? System.getenv("DB_USERNAME") 
        : "admin";  // 修改为您的实际用户名
    
    private static final String DB_PASSWORD = System.getenv("DB_PASSWORD") != null 
        ? System.getenv("DB_PASSWORD") 
        : "your_password_here";  // 修改为您的实际密码
    
    // AWS Aurora MySQL示例（通过环境变量配置）
    // export DB_URL="jdbc:mysql://your-aurora-cluster.cluster-xxxxx.ap-southeast-2.rds.amazonaws.com:3306/database_name?useSSL=false"
    // export DB_USERNAME="admin"
    // export DB_PASSWORD="your_password"
    
    // 如果是Aurora读写分离：
    // Writer endpoint: your-aurora-cluster.cluster-xxxxx.rds.amazonaws.com (写入)
    // Reader endpoint: your-aurora-cluster.cluster-ro-xxxxx.rds.amazonaws.com (只读)
    
    // RDS PostgreSQL示例（如果使用PostgreSQL，取消注释）
    // private static final String DB_URL = "jdbc:postgresql://your-rds-endpoint.region.rds.amazonaws.com:5432/your_database";
    // private static final String DB_USERNAME = "postgres";
    // private static final String DB_PASSWORD = "your_password_here";
    
    // Aurora MySQL示例（如果使用Aurora，取消注释）
    // private static final String DB_URL = "jdbc:mysql://your-aurora-cluster.cluster-xxxxx.region.rds.amazonaws.com:3306/your_database?useSSL=true";
    // private static final String DB_USERNAME = "admin";
    // private static final String DB_PASSWORD = "your_password_here";
    
    /**
     * 初始化Druid连接池
     */
    public static void initDataSource() {
        dataSource = new DruidDataSource();
        
        // 使用上面配置的数据库连接信息
        dataSource.setUrl(DB_URL);
        dataSource.setUsername(DB_USERNAME);
        dataSource.setPassword(DB_PASSWORD);
        
        // 根据URL自动选择驱动
        if (DB_URL.contains("h2")) {
            dataSource.setDriverClassName("org.h2.Driver");
        } else if (DB_URL.contains("mysql")) {
            dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        } else if (DB_URL.contains("postgresql")) {
            dataSource.setDriverClassName("org.postgresql.Driver");
        }
        
       
        dataSource.setInitialSize(5);                    // 初始化连接数
        dataSource.setMinIdle(5);                        // 最小空闲连接数
        dataSource.setMaxActive(10);                      // 最大活跃连接数（测试用小连接池）
        dataSource.setMaxWait(30000);                     // 获取连接最大等待时间(毫秒)
        
        // ===== AWS生产环境配置（与现有Druid配置保持一致）=====
        dataSource.setKeepAlive(false);                    // 开启keepAlive保持连接
        dataSource.setKeepAliveBetweenTimeMillis(35000); // 保活间隔35秒
        dataSource.setTestWhileIdle(true);               // 开启空闲连接检测
        dataSource.setTestOnBorrow(false);                // 获取连接时检测
        dataSource.setTestOnReturn(false);               // 归还时不检测
        dataSource.setValidationQuery("SELECT 1");       // 验证查询语句
        dataSource.setValidationQueryTimeout(5);         // 验证查询超时时间(秒)
        
        // 连接回收配置（与AWS环境一致）
        dataSource.setTimeBetweenEvictionRunsMillis(5000);    // 5秒检测一次（AWS配置）
        dataSource.setMinEvictableIdleTimeMillis(60000);      // 最小空闲1分钟可回收
        dataSource.setMaxEvictableIdleTimeMillis(80000);      // 最大空闲80秒必须回收
        
        // 超时配置（支持长时间查询测试）
        // AWS生产环境: connectTimeout=90s, socketTimeout=90s
        dataSource.setConnectionProperties(
            "druid.stat.mergeSql=true;" +
            "druid.stat.slowSqlMillis=5000;" +
            "connectTimeout=90000;" +                     // 连接超时90秒
            "socketTimeout=0;" +                          // Socket超时设置为0（无超时，支持SLEEP(660)长查询）
            "useSSL=false;" +                             // 本地测试不使用SSL
            "requireSSL=false"                            // 不强制SSL
        );
        
        // 开启详细日志，便于调试
        try {
            dataSource.setFilters("stat");
        } catch (SQLException e) {
            log("Failed to set filters: " + e.getMessage());
        }
        
        // 连接泄漏检测
        dataSource.setRemoveAbandoned(true);
        dataSource.setRemoveAbandonedTimeout(3600);      // 1小时
        dataSource.setLogAbandoned(true);
        
        try {
            dataSource.init();
            log("Druid connection pool initialized successfully");
            log("Database type: " + (DB_URL.contains("h2") ? "H2 in-memory database (local testing)" : "External database"));
            log("Configuration mode: AWS production configuration");
            log("MaxActive: " + dataSource.getMaxActive());
            log("TestOnBorrow: " + dataSource.isTestOnBorrow());
            log("TestWhileIdle: " + dataSource.isTestWhileIdle());
            log("KeepAlive: " + dataSource.isKeepAlive());
            
        } catch (SQLException e) {
            log("ERROR - Failed to initialize Druid connection pool: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 执行长查询并监控断连情况
     */
    public static void executeLongQuery(String sql) {
        DruidPooledConnection conn = null;
        Statement stmt = null;
        ResultSet rs = null;
        
        try {
            log("========== Starting Long Query ==========");
            printPoolStatus();
            
            // Acquire connection
            log("Acquiring database connection...");
            long connStartTime = System.currentTimeMillis();
            conn = dataSource.getConnection();
            long connEndTime = System.currentTimeMillis();
            
            log("✓ Successfully acquired database connection, time taken: " + (connEndTime - connStartTime) + "ms");
            log("Connection object: " + conn.toString());
            
            // Disable auto-commit to keep transaction open
            conn.setAutoCommit(false);
            log("✓ Auto-commit disabled - transaction will remain open without commit");
            
            // Create Statement
            stmt = conn.createStatement();
            stmt.setQueryTimeout(0);  // No timeout for long queries
            
            log("Executing SQL: " + sql);
            log("Query start time: " + dateFormat.format(new Date()));
            
            long startTime = System.currentTimeMillis();
            rs = stmt.executeQuery(sql);
            
            // 获取列信息
            int columnCount = rs.getMetaData().getColumnCount();
            log("\n========== Query Results ==========");
            log("Column count: " + columnCount);
            
            // 打印列名
            StringBuilder header = new StringBuilder();
            for (int i = 1; i <= columnCount; i++) {
                header.append(rs.getMetaData().getColumnName(i));
                if (i < columnCount) header.append(" | ");
            }
            log(header.toString());
            log("--------------------------------------------------");
            
            // 处理结果集
            int count = 0;
            while (rs.next()) {
                count++;
                
                // 打印前10条和后10条记录
                if (count <= 10 || count % 1000 == 0) {
                    StringBuilder row = new StringBuilder();
                    for (int i = 1; i <= columnCount; i++) {
                        row.append(rs.getString(i));
                        if (i < columnCount) row.append(" | ");
                    }
                    log("Row " + count + ": " + row.toString());
                }
                
                // Print progress and connection status every 1000 records
                if (count % 1000 == 0) {
                    log("Progress: " + count + " records, connection status: " + (conn.isClosed() ? "Closed" : "Active"));
                }
            }
            
            long endTime = System.currentTimeMillis();
            long executionTime = endTime - startTime;
            long sessionActiveTime = endTime - connStartTime;
            
            log("========== Query Completed Successfully ==========");
            log("Total rows returned: " + count);
            log("Query execution time: " + executionTime + " ms (" + (executionTime/1000.0) + " seconds)");
            log("Session active time total: " + sessionActiveTime + " ms (" + (sessionActiveTime/1000.0) + " seconds)");
            log("Connection status: " + (conn.isClosed() ? "Closed" : "Active"));
            log("Query end time: " + dateFormat.format(new Date()));
            
        } catch (SQLException e) {
            log("!!!!! SQL Exception Occurred !!!!!");
            log("Exception type: " + e.getClass().getName());
            log("Error code: " + e.getErrorCode());
            log("SQL state: " + e.getSQLState());
            log("Error message: " + e.getMessage());
            
            // Check if it's a connection error
            if (isConnectionError(e)) {
                log("*** Database disconnection detected! ***");
            }
            
            e.printStackTrace();
        } finally {
            log("Cleaning up resources...");
            closeResource(rs, stmt, conn);
            printPoolStatus();
        }
    }
    
    /**
     * 测试长查询 + 空闲等待 + 重新查询场景
     * 
     */
    public static void testLongQueryWithIdle(String sql, int idleSeconds) {
        log("\n########## Test Scenario: Long Query + " + idleSeconds + "s Idle + Re-query ##########");
        
        // First query
        log("\n--- First Query ---");
        executeLongQuery(sql);
        
        // Wait
        log("\n--- Starting Idle Wait for " + idleSeconds + " seconds ---");
        log("Wait start time: " + dateFormat.format(new Date()));
        printPoolStatus();
        
        try {
            for (int i = 1; i <= idleSeconds; i++) {
                Thread.sleep(1000);
                if (i % 30 == 0) {  // Print every 30 seconds
                    log("Waited " + i + "/" + idleSeconds + " seconds...");
                    printPoolStatus();
                }
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        
        log("Wait end time: " + dateFormat.format(new Date()));
        
        // Second query
        log("\n--- Second Query (Test for Disconnection) ---");
        executeLongQuery(sql);
        
        log("\n########## Test Scenario Completed ##########\n");
    }
    
    /**
     * 判断是否为连接断开错误
     */
    private static boolean isConnectionError(SQLException e) {
        String msg = e.getMessage().toLowerCase();
        int errorCode = e.getErrorCode();
        
        return msg.contains("connection") && 
               (msg.contains("closed") || 
                msg.contains("broken") || 
                msg.contains("reset") ||
                msg.contains("timeout") ||
                msg.contains("lost") ||
                errorCode == 0 ||
                errorCode == 2006 ||  // MySQL: MySQL server has gone away
                errorCode == 2013);   // MySQL: Lost connection to MySQL server
    }

    /**
     * 关闭资源
     */
    private static void closeResource(ResultSet rs, Statement stmt, DruidPooledConnection conn) {
        try {
            if (rs != null) rs.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        try {
            if (stmt != null) stmt.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        try {
            if (conn != null) conn.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * 
     * 本地测试用。 
     */
    // public static void initTestData() {
    //     DruidPooledConnection conn = null;
    //     Statement stmt = null;
    //     
    //     try {
    //         log("========== 初始化测试数据 ==========");
    //         conn = dataSource.getConnection();
    //         stmt = conn.createStatement();
    //         
    //         // 创建测试表
    //         log("创建测试表 test_table...");
    //         stmt.execute("DROP TABLE IF EXISTS test_table");
    //         stmt.execute("CREATE TABLE test_table (" +
    //                     "id INT PRIMARY KEY, " +
    //                     "name VARCHAR(100), " +
    //                     "amount DECIMAL(10,2), " +
    //                     "created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
    //         
    //         // 插入测试数据
    //         log("插入测试数据...");
    //         for (int i = 1; i <= 10000; i++) {
    //             stmt.addBatch("INSERT INTO test_table (id, name, amount) VALUES (" + 
    //                          i + ", 'Test-" + i + "', " + (Math.random() * 1000) + ")");
    //             
    //             if (i % 1000 == 0) {
    //                 stmt.executeBatch();
    //                 log("已插入 " + i + " 条记录");
    //             }
    //         }
    //         stmt.executeBatch();
    //         
    //         log("✓ 测试数据初始化完成：共10000条记录");
    //         
    //     } catch (SQLException e) {
    //         log("ERROR - 初始化测试数据失败: " + e.getMessage());
    //         e.printStackTrace();
    //     } finally {
    //         try {
    //             if (stmt != null) stmt.close();
    //             if (conn != null) conn.close();
    //         } catch (SQLException e) {
    //             e.printStackTrace();
    //         }
    //     }
    // }

    /**
     * 关闭连接池
     */
    public static void closeDataSource() {
        if (dataSource != null) {
            dataSource.close();
            System.out.println("Druid connection pool closed");
        }
    }

    /**
     * 打印连接池状态
     */
    public static void printPoolStatus() {
        if (dataSource != null) {
            log("\n===== Druid Connection Pool Status [" + dateFormat.format(new Date()) + "] =====");
            log("Active connections: " + dataSource.getActiveCount());
            log("Idle connections: " + dataSource.getPoolingCount());
            log("Waiting threads: " + dataSource.getWaitThreadCount());
            log("Total connections created: " + dataSource.getCreateCount());
            log("Total connections destroyed: " + dataSource.getDestroyCount());
            log("Connection errors: " + dataSource.getErrorCount());
            log("===========================================================\n");
        }
    }
    
    /**
     * 日志输出
     */
    private static void log(String message) {
        System.out.println("[" + dateFormat.format(new Date()) + "] " + message);
    }

    /**
     * 主方法 - 配置不同的测试场景
     */
    public static void main(String[] args) {
        try {
            log("========================================");
            log("  Druid Database Disconnection Testing Tool");
            log("  Environment: AWS");
            log("========================================\n");
            
            // 打印AWS环境信息（如果设置了环境变量）
            String awsRegion = System.getenv("AWS_REGION");
            String ec2InstanceId = System.getenv("EC2_INSTANCE_ID");
            if (awsRegion != null) {
                log("AWS Region: " + awsRegion);
            }
            if (ec2InstanceId != null) {
                log("EC2 Instance ID: " + ec2InstanceId);
            }
            
            // 1. 初始化连接池
            initDataSource();
            printPoolStatus();
            
            // 2. 初始化测试数据（仅H2数据库 - 本地测试用，AWS部署时已注释）
            // if (DB_URL.contains("h2")) {
            //     initTestData();
            // }
            
            // 3. Configure test SQL (modify to your actual table and query)
            String sql = "select sleep(660), count(distinct a.mt4_account) from tb_account_mt4 a join tb_user on a.user_id = tb_user.id and tb_user.is_del = 0 join tb_user_relation ur_parent on a.user_id = ur_parent.user_id and ur_parent.is_del = 0 join tb_user_account_mt4_relation on a.mt4_account = tb_user_account_mt4_relation.mt4_account and tb_user_account_mt4_relation.is_del = 0 join tb_user ua_parent on tb_user_account_mt4_relation.p_id = ua_parent.id left join tb_user_extends on a.user_id = tb_user_extends.user_id join tb_user_outer on a.user_id = tb_user_outer.user_id where a.is_del = 0 and a.mt4_account is not null and ur_parent.org_id in (1,100,101,103,121,286,105,119,287,288,154,156,155,219,220,221,226,164,368,169,170,176,177,215,289,172,184,290,185,173,228,239,264,355,357,358,367,374,379,381,387,388,389,241,337,346,377,378,338,339,340,342,350,351,380,385,386,123,136,187,364,137,138,160,188,168,190,200,201,277,278,269,270,275,276,375,285,124,125,126,128,189,191,192,196,197,222,371,372,223,227,291,292,365,274,352,363,370,373,376,393,353,356,359,360,361,362,382,390,391,366,383,384,392,127,133,134,135,139,140,141,159,161,354,165,166,167,193) and (a.is_archive = 0 or a.is_archive is null) and a.accountDealType = 3 and a.approved_time >= '2015-01-01 00:00:00' and a.approved_time <= '2026-01-19 15:00:08'";
            
            log("Test SQL: " + sql);
            log("\n===== Loop Execution Mode: Run for 24 hours, execute every 1 second =====\n");
            
            long startTime = System.currentTimeMillis();
            long duration = 24 * 60 * 60 * 1000; // 24 hours in milliseconds
            long endTime = startTime + duration;
            int executionCount = 0;
            
            log("Start time: " + dateFormat.format(new Date(startTime)));
            log("End time (estimated): " + dateFormat.format(new Date(endTime)));
            log("Press Ctrl+C to stop anytime\n");
            
            while (System.currentTimeMillis() < endTime) {
                executionCount++;
                log("\n########## Execution #" + executionCount + " ##########");
                executeLongQuery(sql);
                
                // Wait 1 second before next execution
                long remaining = endTime - System.currentTimeMillis();
                if (remaining > 0) {
                    long sleepTime = Math.min(1000, remaining);
                    log("\nWaiting 1 second before next execution...");
                    Thread.sleep(sleepTime);
                }
            }
            
            log("\n========== Loop Execution Completed ==========");
            log("Total executions: " + executionCount);
            log("Total duration: " + (System.currentTimeMillis() - startTime) / 1000.0 / 3600.0 + " hours");
            
            printPoolStatus();
            
        } catch (Exception e) {
            log("!!!!! Program execution error !!!!!");
            log("Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Close connection pool
            closeDataSource();
        }
    }
}
