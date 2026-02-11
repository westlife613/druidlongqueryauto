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

    private static DruidDataSource dataSource;   // Read Replica endpoint for long queries
    // SimpleDateFormat不是线程安全的，但本程序是单线程运行，可以忽略警告
    @SuppressWarnings("SimpleDateFormat")
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    // ===== 数据库配置 =====
    // Read Replica endpoint for long queries (DDL 在另一个实例手动执行)
    private static final String DB_URL = System.getenv("DB_URL") != null 
        ? System.getenv("DB_URL") 
        : "jdbc:mysql://your-aurora-cluster.cluster-ro-xxxxx.ap-southeast-2.rds.amazonaws.com:3306/your_database?useSSL=false&serverTimezone=UTC&connectTimeout=90000&socketTimeout=0";
    
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
     * 初始化Druid连接池（Read Replica）
     */
    public static void initDataSource() {
        dataSource = new DruidDataSource();
        dataSource.setUrl(DB_URL);
        dataSource.setUsername(DB_USERNAME);
        dataSource.setPassword(DB_PASSWORD);
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setInitialSize(5);
        dataSource.setMinIdle(5);
        dataSource.setMaxActive(20);
        dataSource.setMaxWait(30000);
        
        // ===== AWS生产环境配置 =====
        dataSource.setKeepAlive(true);
        dataSource.setKeepAliveBetweenTimeMillis(35000);
        dataSource.setTestWhileIdle(true);
        dataSource.setTestOnBorrow(true);
        dataSource.setTestOnReturn(false);
        dataSource.setValidationQuery("SELECT 1");
        dataSource.setValidationQueryTimeout(5);
        
        dataSource.setTimeBetweenEvictionRunsMillis(5000);
        dataSource.setMinEvictableIdleTimeMillis(60000);
        dataSource.setMaxEvictableIdleTimeMillis(80000);
        
        dataSource.setConnectionProperties(
            "druid.stat.mergeSql=true;" +
            "druid.stat.slowSqlMillis=5000"
        );
        
        try {
            dataSource.setFilters("stat");
        } catch (SQLException e) {
            log("Failed to set filters: " + e.getMessage());
        }
        
        dataSource.setRemoveAbandoned(true);
        dataSource.setRemoveAbandonedTimeout(3600);
        dataSource.setLogAbandoned(true);
        
        try {
            dataSource.init();
            log("Druid connection pool initialized successfully");
            log("=== DB endpoint: " + DB_URL);
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
            log("Connection pool closed");
        }
    }

    /**
     * 打印连接池状态
     */
    public static void printPoolStatus() {
        if (dataSource != null) {
            log("\n===== Connection Pool Status [" + dateFormat.format(new Date()) + "] =====");
            log("Active connections: " + dataSource.getActiveCount());
            log("Idle connections: " + dataSource.getPoolingCount());
            log("Waiting threads: " + dataSource.getWaitThreadCount());
            log("Total connections created: " + dataSource.getCreateCount());
            log("Total connections destroyed: " + dataSource.getDestroyCount());
            log("Connection errors: " + dataSource.getErrorCount());
            log("===========================================================");
        }
    }
    
    /**
     * 日志输出
     */
    private static void log(String message) {
        System.out.println("[" + dateFormat.format(new Date()) + "] " + message);
    }

    /**
     * 主方法 - 简单 SLEEP 查询模式
     * DDL 由另一个实例执行
     */
    public static void main(String[] args) {
        try {
            log("========================================");
            log("  Druid Long Query Testing Tool");
            log("  Mode: SELECT + SLEEP(10) - 持续10秒的查询");
            log("  Note: 启动后5秒内在Primary执行DDL");
            log("========================================\n");
            
            // 1. 初始化连接池
            initDataSource();
            printPoolStatus();
            
            // 2. 定义慢查询SQL - SELECT + SLEEP 确保持续10秒
            // 目的：复现 Aurora 在 DDL 执行时断开正在执行的 SQL 连接
            // 场景：
            //   10:00:00 Reader (SELECT) - 开始执行，持续10秒
            //   10:00:05 Writer (DDL)    - 5秒后执行 ALTER TABLE
            //   10:00:06 Reader          - 连接被中断？
            final String longQuerySQL = "SELECT *, SLEEP(10) FROM big_table LIMIT 1";
            
            // 3. 测试参数
            final int threadCount = 1;  // 单线程执行
            final int loopCount = 100;  // 循环执行次数
            final long startTime = System.currentTimeMillis();
            
            log("测试模式: SELECT *, SLEEP(10) FROM big_table - 每次查询持续10秒");
            log("目的: 复现 Aurora DDL 执行时断开正在执行的 SQL 连接");
            log("======================================================");
            log("  测试步骤:");
            log("  1. [Reader] Java程序开始执行 SELECT (持续10秒)");
            log("  2. [Writer] 等待约5秒后，在Primary执行DDL:");
            log("     ALTER TABLE big_table ADD COLUMN temp_col INT;");
            log("  3. [Reader] 观察SELECT是否被中断");
            log("======================================================");
            log("Thread count: " + threadCount);
            log("Loop count: " + loopCount);
            log("Each query duration: 10 seconds (SLEEP)");
            log("SQL: " + longQuerySQL);
            log("Start time: " + dateFormat.format(new Date(startTime)));
            log("Press Ctrl+C to stop anytime\n");

            // 4. 循环执行 SLEEP 查询
            for (int i = 1; i <= loopCount; i++) {
                log("\n========== Loop " + i + "/" + loopCount + " ==========");
                try {
                    executeLongQuery(longQuerySQL);
                    log("Loop " + i + " completed successfully");
                } catch (Exception e) {
                    log("Loop " + i + " ★★★ Exception: " + e.getMessage() + " ★★★");
                    e.printStackTrace();
                }
                
                // 每轮之间短暂等待
                Thread.sleep(1000);
            }
            
            log("\n========== All Loops Completed ==========");
            log("Total duration: " + (System.currentTimeMillis() - startTime) / 1000.0 / 60.0 + " minutes");
            printPoolStatus();
        } catch (Exception e) {
            log("!!!!! Program execution error !!!!!");
            log("Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            closeDataSource();
        }
    }
}
