-- 切流（决策级多版本流量分配）演示数据：可重复执行，先清后建
-- 用途：在「策略管理」页体验 70/30 切流、草稿发布、已下线版本复活等交互
SET @old = (SELECT id FROM strategy WHERE code = 'traffic_demo');
DELETE r FROM grayscale_rule r JOIN strategy_version v ON r.version_id = v.id WHERE v.strategy_id = @old;
DELETE FROM strategy_version WHERE strategy_id = @old;
DELETE FROM strategy WHERE id = @old;

INSERT INTO strategy (code, name, category, description, owner)
VALUES ('traffic_demo', '切流验证示例', '决策中心', '切流（多版本流量分配）功能演示数据，可随意修改或删除', 'tester');
SET @sid = LAST_INSERT_ID();

INSERT INTO strategy_version
  (strategy_id, version_no, rev, status, state_label, bundle, change_count, grayscale_pct, submitted_by, published_by, published_at)
VALUES
  (@sid, 'v0.1', 3, 'PUBLISHED',   '生产运行中', 'sb_v0.1_r3', 2, 70, 'alice', 'alice', NOW()),
  (@sid, 'v0.2', 1, 'PUBLISHED',   '灰度观察中', 'sb_v0.2_r1', 1, 30, 'alice', 'alice', NOW()),
  (@sid, 'v0.3', 2, 'DRAFT',       'v0.3 草稿',  NULL,         0, NULL, 'alice', NULL,  NULL),
  (@sid, 'v0.4', 1, 'ROLLED_BACK', '已下线',    'sb_v0.4_r1', 1, 0,   'alice', 'alice', NOW()),
  (@sid, 'v0.5', 1, 'ROLLED_BACK', '已下线',    'sb_v0.5_r1', 1, 0,   'alice', 'alice', NOW());

SET @v1 = (SELECT id FROM strategy_version WHERE strategy_id = @sid AND version_no = 'v0.1');
SET @v2 = (SELECT id FROM strategy_version WHERE strategy_id = @sid AND version_no = 'v0.2');
INSERT INTO grayscale_rule (version_id, mode, traffic_pct, whitelist_users, channel, user_tag, status)
VALUES (@v1, 'PERCENT', 70, 0, NULL, NULL, 'ACTIVE'),
       (@v2, 'PERCENT', 30, 0, NULL, NULL, 'ACTIVE');

SELECT @sid AS strategy_id,
       (SELECT GROUP_CONCAT(CONCAT(version_no, '=', id) ORDER BY id)
        FROM strategy_version WHERE strategy_id = @sid) AS versions;
