-- =============================================================================
--  特征「编码 / 入参 / 出参」可编辑 支撑迁移
--  日期：2026-10-13
--  执行：mysql -uroot -proot risk_platform < database/migration-2026-10-13-feature-editable.sql
--
--  背景：
--    · 衍生特征原先固定只有 1 个出参（前端写死 result），现在出参改为可增删改的名称/类型列表；
--    · 入参原先只能展示自动解析结果，现在允许调类型 / 必填 / 默认值、移除行、手工补充决策入参；
--    · 验证结果也随之从「单一衍生输出值」改成「按出参清单逐项展示值」。
-- =============================================================================

-- 1) 特征入参登记表补齐可编辑属性
ALTER TABLE `feature_input_var`
  ADD COLUMN `required`      TINYINT(1)   NOT NULL DEFAULT 1  COMMENT '是否必填：0=选填' AFTER `source`,
  ADD COLUMN `default_value` VARCHAR(255) NULL               COMMENT '默认测试值（验证时预填）' AFTER `required`,
  ADD COLUMN `enabled`       TINYINT(1)   NOT NULL DEFAULT 1  COMMENT '是否参与验证：0=已被用户移除' AFTER `default_value`;

-- 2) 特征输出变量表（一个特征可有多个出参）
CREATE TABLE IF NOT EXISTS `feature_output_var` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `feature_id`  BIGINT       NOT NULL,
  `var_code`    VARCHAR(128) NOT NULL COMMENT '出参名，如 result / risk_level / score',
  `var_type`    VARCHAR(32)  NOT NULL DEFAULT 'STRING'
                COMMENT 'STRING / INTEGER / LONG / DECIMAL / BOOLEAN / DATETIME',
  `description` VARCHAR(255) NULL COMMENT '出参说明',
  `sort_no`     INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_fovar_feature` (`feature_id`)
) ENGINE=InnoDB COMMENT='特征输出变量';

-- 3) 存量衍生特征补一个默认出参，保持与改造前的行为一致（原来固定输出 result）
INSERT INTO `feature_output_var` (feature_id, var_code, var_type, sort_no)
SELECT f.id, 'result', COALESCE(NULLIF(f.data_type, ''), 'STRING'), 0
FROM `feature` f
WHERE f.deleted = 0
  AND f.feature_type = 'DERIVED'
  AND NOT EXISTS (SELECT 1 FROM `feature_output_var` o WHERE o.feature_id = f.id);

-- 4) 出参数量与出参表对齐
UPDATE `feature` f
   SET f.output_count = (SELECT COUNT(*) FROM `feature_output_var` o WHERE o.feature_id = f.id)
 WHERE f.deleted = 0
   AND f.feature_type = 'DERIVED';
