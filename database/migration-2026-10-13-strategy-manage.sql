-- 策略管理：版本流量分配支持 随机切流 / 百分比切流 / 白名单切流
-- 执行方式（需手动执行）：
--   "E:/progame/env/mysql-8.0.41-winx64/mysql-8.0.41-winx64/bin/mysql.exe" -uroot -proot risk_platform < database/migration-2026-10-13-strategy-manage.sql

USE `risk_platform`;

-- 1) 灰度规则增加切流方式：RANDOM 随机切流 / PERCENT 百分比切流 / WHITELIST 白名单切流
ALTER TABLE `grayscale_rule`
  ADD COLUMN `mode` VARCHAR(16) NOT NULL DEFAULT 'PERCENT'
  COMMENT 'RANDOM 随机切流 / PERCENT 百分比切流 / WHITELIST 白名单切流'
  AFTER `version_id`;

-- 2) 补齐各版本流量规则，保证版本列表每个版本都有流量比例可展示
--    v11（id=1）线上全量 · v12（id=2）灰度10% · v8（id=3）灰度20%
DELETE FROM `grayscale_rule` WHERE `version_id` IN (1, 2, 3);

INSERT INTO `grayscale_rule` (`version_id`, `mode`, `traffic_pct`, `whitelist_users`, `channel`, `user_tag`, `status`) VALUES
  (1, 'PERCENT',   100, 0,  NULL,      NULL, 'ACTIVE'),
  (2, 'PERCENT',   10,  50, 'APP_IOS', 'VIP', 'ACTIVE'),
  (3, 'WHITELIST', 20,  120, NULL,     'VIP', 'ACTIVE');

-- 3) 让版本表承接的放量比例与规则一致
UPDATE `strategy_version` SET `grayscale_pct` = 100 WHERE `id` = 1;
UPDATE `strategy_version` SET `grayscale_pct` = 10  WHERE `id` = 2;
UPDATE `strategy_version` SET `grayscale_pct` = 20  WHERE `id` = 3;

-- 说明：策略本身的「草稿 / 已发布」状态不落库，由该策略下版本状态推导
--       （存在 PUBLISHED / GRAYSCALE 版本即视为已发布），因此无需新增字段。
