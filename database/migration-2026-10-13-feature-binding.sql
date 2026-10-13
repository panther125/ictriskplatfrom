-- ---------------------------------------------------------------------------
-- 迁移：特征支持「基础特征 / 衍生特征」两类创建方式
--   · 基础特征：绑定一个视图的【一个出参】作为特征输出值，验证时复用该视图的入参
--   · 衍生特征：用 @@base_feature_code 引用基础特征，验证时自动解析被引用特征的入参
--
-- 兼容性：仅新增列，历史数据不受影响（历史行新列均为 NULL，仍按原 SQL 表达式逻辑工作）。
-- 执行：mysql -uroot -proot risk_platform < database/migration-2026-10-13-feature-binding.sql
-- ---------------------------------------------------------------------------

ALTER TABLE `feature`
  ADD COLUMN `view_id`    BIGINT       NULL COMMENT '【基础特征】绑定的视图 id，1:1 引用该视图的一个出参' AFTER `sql_expr`,
  ADD COLUMN `view_code`  VARCHAR(128) NULL COMMENT '【基础特征】绑定视图编码（创建时快照，便于血缘与失效检测）' AFTER `view_id`,
  ADD COLUMN `view_field` VARCHAR(128) NULL COMMENT '【基础特征】引用的视图出参字段名，即特征输出值' AFTER `view_code`,
  ADD COLUMN `expr`       TEXT         NULL COMMENT '【衍生特征】加工表达式，用 @@base_feature_code 引用基础特征' AFTER `view_field`;

-- 便于按视图反查绑定的基础特征（视图字段变更时的影响面分析）
ALTER TABLE `feature`
  ADD INDEX `idx_feature_view` (`view_id`, `deleted`);

-- feature_input_var.source 增加 PARAM 取值语义（注释级变更，列类型不变）
ALTER TABLE `feature_input_var`
  MODIFY COLUMN `source` VARCHAR(16) NOT NULL DEFAULT 'FEATURE'
    COMMENT 'FEATURE基础特征引用 / VIEW视图入参 / PARAM决策入参 / BUILTIN_TIME系统内置';
