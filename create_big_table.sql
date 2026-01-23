-- 创建大表
CREATE TABLE IF NOT EXISTS big_table (
  id INT PRIMARY KEY AUTO_INCREMENT,
  col1 INT,
  col2 VARCHAR(100)
);

-- 插入100万行测试数据
INSERT INTO big_table (col1, col2)
SELECT FLOOR(RAND()*10000), REPEAT('x', 80)
FROM information_schema.COLUMNS t1, information_schema.COLUMNS t2
LIMIT 1000000;
