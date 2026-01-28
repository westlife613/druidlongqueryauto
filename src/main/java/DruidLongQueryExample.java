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

    private static DruidDataSource primaryDataSource;   // Primary endpoint for DDL
    private static DruidDataSource replicaDataSource;   // Read Replica endpoint for long queries
    // SimpleDateFormat不是线程安全的，但本程序是单线程运行，可以忽略警告
    @SuppressWarnings("SimpleDateFormat")
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    // ===== 数据库配置 =====
    // Primary endpoint for DDL operations
    private static final String PRIMARY_DB_URL = System.getenv("PRIMARY_DB_URL") != null 
        ? System.getenv("PRIMARY_DB_URL") 
        : "jdbc:mysql://your-aurora-cluster.cluster-xxxxx.ap-southeast-2.rds.amazonaws.com:3306/your_database?useSSL=false&serverTimezone=UTC&connectTimeout=90000&socketTimeout=0";
    
    // Read Replica endpoint for long queries
    private static final String REPLICA_DB_URL = System.getenv("REPLICA_DB_URL") != null 
        ? System.getenv("REPLICA_DB_URL") 
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
     * 初始化Druid连接池（Primary和Read Replica）
     */
    public static void initDataSources() {
        // Primary数据源 - 用于DDL操作
        primaryDataSource = new DruidDataSource();
        primaryDataSource.setUrl(PRIMARY_DB_URL);
        primaryDataSource.setUsername(DB_USERNAME);
        primaryDataSource.setPassword(DB_PASSWORD);
        primaryDataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        primaryDataSource.setInitialSize(2);
        primaryDataSource.setMinIdle(1);
        primaryDataSource.setMaxActive(5);
        primaryDataSource.setMaxWait(30000);
        
        // Read Replica数据源 - 用于长查询
        replicaDataSource = new DruidDataSource();
        replicaDataSource.setUrl(REPLICA_DB_URL);
        replicaDataSource.setUsername(DB_USERNAME);
        replicaDataSource.setPassword(DB_PASSWORD);
        replicaDataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        replicaDataSource.setInitialSize(5);
        replicaDataSource.setMinIdle(5);
        replicaDataSource.setMaxActive(20);
        replicaDataSource.setMaxWait(30000);
        
        // ===== AWS生产环境配置（应用到两个数据源）=====
        for (DruidDataSource ds : new DruidDataSource[]{primaryDataSource, replicaDataSource}) {
            ds.setKeepAlive(true);
            ds.setKeepAliveBetweenTimeMillis(35000);
            ds.setTestWhileIdle(true);
            ds.setTestOnBorrow(true);
            ds.setTestOnReturn(false);
            ds.setValidationQuery("SELECT 1");
            ds.setValidationQueryTimeout(5);
            
            ds.setTimeBetweenEvictionRunsMillis(5000);
            ds.setMinEvictableIdleTimeMillis(60000);
            ds.setMaxEvictableIdleTimeMillis(80000);
            
            ds.setConnectionProperties(
                "druid.stat.mergeSql=true;" +
                "druid.stat.slowSqlMillis=5000"
            );
            
            try {
                ds.setFilters("stat");
            } catch (SQLException e) {
                log("Failed to set filters: " + e.getMessage());
            }
            
            ds.setRemoveAbandoned(true);
            ds.setRemoveAbandonedTimeout(3600);
            ds.setLogAbandoned(true);
        }
        
        try {
            primaryDataSource.init();
            replicaDataSource.init();
            log("Druid connection pools initialized successfully");
            log("=== PRIMARY (DDL) endpoint: " + PRIMARY_DB_URL);
            log("=== REPLICA (Long Query) endpoint: " + REPLICA_DB_URL);
            log("Primary MaxActive: " + primaryDataSource.getMaxActive());
            log("Replica MaxActive: " + replicaDataSource.getMaxActive());
            log("TestOnBorrow: " + primaryDataSource.isTestOnBorrow());
            log("TestWhileIdle: " + primaryDataSource.isTestWhileIdle());
            log("KeepAlive: " + primaryDataSource.isKeepAlive());
            
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
            log("========== Starting Long Query on READ REPLICA ==========");
            printReplicaPoolStatus();
            
            // Acquire connection from Read Replica
            log("Acquiring database connection from Read Replica...");
            long connStartTime = System.currentTimeMillis();
            conn = replicaDataSource.getConnection();
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
     * 执行DDL干扰操作，模拟生产环境中的DDL操作冲突
     */
    public static void executeDDLInterference(int intervalSeconds) {
        DruidPooledConnection conn = null;
        Statement stmt = null;
        
        try {
            log("[DDL-Thread] DDL干扰线程启动，使用PRIMARY endpoint，每" + intervalSeconds + "秒执行一次DDL操作");
            
            int ddlCycle = 0;
            
            while (true) {
                ddlCycle++;
                
                // 每次循环执行一组完整的DDL操作
                String tempColName = "temp_col_" + System.currentTimeMillis();
                
                try {
                    // 操作1: ADD COLUMN (on PRIMARY)
                    log("[DDL-Thread] 准备执行DDL Cycle #" + ddlCycle + " - Step 1: ADD COLUMN on PRIMARY");
                    conn = primaryDataSource.getConnection();
                    stmt = conn.createStatement();
                    long startTime = System.currentTimeMillis();
                    stmt.execute("ALTER TABLE big_table ADD COLUMN " + tempColName + " VARCHAR(500)");
                    log("[DDL-Thread] ✓ ADD COLUMN成功，耗时: " + (System.currentTimeMillis() - startTime) + "ms");
                    stmt.close();
                    conn.close();
                    
                    Thread.sleep(1000);
                    
                    // 操作2: UPDATE (on PRIMARY)
                    log("[DDL-Thread] 准备执行DDL Cycle #" + ddlCycle + " - Step 2: UPDATE on PRIMARY");
                    conn = primaryDataSource.getConnection();
                    stmt = conn.createStatement();
                    startTime = System.currentTimeMillis();
                    stmt.execute("UPDATE big_table SET " + tempColName + " = REPEAT('X', 500) WHERE MOD(col1, 5) = 0");
                    log("[DDL-Thread] ✓ UPDATE成功，耗时: " + (System.currentTimeMillis() - startTime) + "ms");
                    stmt.close();
                    conn.close();
                    
                    Thread.sleep(1000);
                    
                    // 操作3: DROP COLUMN (on PRIMARY)
                    log("[DDL-Thread] 准备执行DDL Cycle #" + ddlCycle + " - Step 3: DROP COLUMN on PRIMARY");
                    conn = primaryDataSource.getConnection();
                    stmt = conn.createStatement();
                    startTime = System.currentTimeMillis();
                    stmt.execute("ALTER TABLE big_table DROP COLUMN " + tempColName);
                    log("[DDL-Thread] ✓ DROP COLUMN成功，耗时: " + (System.currentTimeMillis() - startTime) + "ms");
                    stmt.close();
                    conn.close();
                    
                } catch (SQLException e) {
                    log("[DDL-Thread] ✗ DDL执行失败: " + e.getMessage());
                } finally {
                    try {
                        if (stmt != null) stmt.close();
                        if (conn != null) conn.close();
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                }
                
                // DDL执行完成后，等待间隔时间再执行下一轮
                log("[DDL-Thread] DDL Cycle #" + ddlCycle + " 完成，等待" + intervalSeconds + "秒后执行下一轮");
                Thread.sleep(intervalSeconds * 1000);
            }
            
        } catch (InterruptedException e) {
            log("[DDL-Thread] DDL线程被中断");
        } catch (Exception e) {
            log("[DDL-Thread] DDL线程错误: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 关闭连接池
     */
    public static void closeDataSource() {
        if (primaryDataSource != null) {
            primaryDataSource.close();
            log("Primary connection pool closed");
        }
        if (replicaDataSource != null) {
            replicaDataSource.close();
            log("Replica connection pool closed");
        }
    }

    /**
     * 打印连接池状态
     */
    public static void printPoolStatus() {
        printPrimaryPoolStatus();
        printReplicaPoolStatus();
    }
    
    public static void printPrimaryPoolStatus() {
        if (primaryDataSource != null) {
            log("\n===== PRIMARY Connection Pool Status [" + dateFormat.format(new Date()) + "] =====");
            log("Active connections: " + primaryDataSource.getActiveCount());
            log("Idle connections: " + primaryDataSource.getPoolingCount());
            log("Waiting threads: " + primaryDataSource.getWaitThreadCount());
            log("Total connections created: " + primaryDataSource.getCreateCount());
            log("Total connections destroyed: " + primaryDataSource.getDestroyCount());
            log("Connection errors: " + primaryDataSource.getErrorCount());
            log("===========================================================\n");
        }
    }
    
    public static void printReplicaPoolStatus() {
        if (replicaDataSource != null) {
            log("\n===== READ REPLICA Connection Pool Status [" + dateFormat.format(new Date()) + "] =====");
            log("Active connections: " + replicaDataSource.getActiveCount());
            log("Idle connections: " + replicaDataSource.getPoolingCount());
            log("Waiting threads: " + replicaDataSource.getWaitThreadCount());
            log("Total connections created: " + replicaDataSource.getCreateCount());
            log("Total connections destroyed: " + replicaDataSource.getDestroyCount());
            log("Connection errors: " + replicaDataSource.getErrorCount());
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
     * 主方法 - 单线程慢查询 + DDL干扰模式（复现Read Replica lag场景）
     */
    public static void main(String[] args) {
        try {
            log("========================================");
            log("  Druid Replica Lag Testing Tool");
            log("  Environment: AWS Aurora (Primary + Read Replica)");
            log("========================================\n");
            
            // 1. 初始化连接池
            initDataSources();
            printPoolStatus();
            
            // 2. 定义慢查询SQL（~90秒）
            final String sql = "SELECT COUNT(*), SUM(t1.col1 * t2.col1) FROM big_table t1, big_table t2 WHERE t1.col1 < 500 AND t2.col1 < 500";
            log("Test SQL (预计90秒): " + sql + "\n");
            
            // 3. 测试参数
            final int threadCount = 1;  // 单线程
            final int ddlIntervalSeconds = 30;  // DDL每30秒执行一次
            final long duration = 60 * 60 * 1000; // 1小时
            final long startTime = System.currentTimeMillis();
            
            log("测试模式: 单线程慢查询(Read Replica) + DDL干扰(Primary)");
            log("目的: 复现Primary DDL导致Read Replica lag强制断开场景");
            log("Thread count: " + threadCount);
            log("DDL interval: " + ddlIntervalSeconds + "秒");
            log("Duration: " + duration / 1000 / 60 + "分钟");
            log("Start time: " + dateFormat.format(new Date(startTime)));
            log("预计结束时间: " + dateFormat.format(new Date(startTime + duration)));
            log("Press Ctrl+C to stop anytime\n");

            // 4. 启动DDL干扰线程
            Thread ddlThread = new Thread(() -> {
                try {
                    Thread.sleep(10000); // 等待10秒，确保慢查询先开始，获取锁
                    log("[DDL-Thread] *** DDL干扰线程开始执行 ***\n");
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                executeDDLInterference(ddlIntervalSeconds);
            }, "DDL-Interference-Thread");
            ddlThread.setDaemon(true);
            ddlThread.start();
            log("DDL干扰线程已启动，将等待10秒后开始干扰，确保慢查询先获取锁！\n");

            // 5. 启动慢查询线程（只执行一次）
            Thread[] threads = new Thread[threadCount];
            for (int i = 0; i < threadCount; i++) {
                final int threadId = i + 1;
                threads[i] = new Thread(() -> {
                    log("[Thread-" + threadId + "] Started on READ REPLICA - Single execution mode");
                    log("[Thread-" + threadId + "] === Executing Long Query ===");
                    try {
                        executeLongQuery(sql);
                        log("[Thread-" + threadId + "] Completed successfully");
                    } catch (Exception e) {
                        log("[Thread-" + threadId + "] Exception: " + e.getMessage());
                        e.printStackTrace();
                    }
                }, "SlowQueryThread-" + (i + 1));
                threads[i].start();
                log("Thread-" + threadId + " started on Read Replica");
            }
            
            log("\n所有线程已启动，开始测试...\n");
            
            for (int i = 0; i < threadCount; i++) {
                try {
                    threads[i].join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            
            log("\n========== All Threads Completed ==========");
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
