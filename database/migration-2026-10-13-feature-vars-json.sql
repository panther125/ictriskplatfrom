-- =============================================================================
--  特征「入参 / 出参」由独立表改为 feature 表内的 JSON 列
--  日期：2026-10-13
--  执行：mysql -uroot -proot risk_platform < database/migration-2026-10-13-feature-vars-json.sql
--
--  背景：
--    · 原先入参落 feature_input_var、出参落 feature_output_var 两张子表；
--      一个特征的入参/出参本质上是它的「定义快照」，随特征一起读、一起写，
--      拆表后每次保存都要先删后插、还多两次 join，收益不大。
--    · 改为在 feature 上直接存两列 JSON（与 data_source_view.input_vars_json 的思路一致）：
--        input_vars_json  : [{varCode, varType, source, required, defaultValue, enabled}]
--        output_vars_json : [{varCode, varType, description, sortNo}]
--      入参的默认测试值（原来的 default_value 列）也一并收进 input_vars_json 的 defaultValue 字段。
--    · 迁移完成后删除 feature_input_var / feature_output_var 两张表。
--
--  说明：本脚本按「转换存量数据、不新增臆造数据」的原则编写 —— 原来没有出参记录的特征
--        （如基础特征）迁移后同样为空，行为与迁移前完全一致；运行时兜底逻辑保持不变。
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1) feature 增加两列 JSON
-- ---------------------------------------------------------------------------
ALTER TABLE `feature`
  ADD COLUMN `input_vars_json`  JSON NULL COMMENT '入参定义：[{varCode,varType,source,required,defaultValue,enabled}]' AFTER `output_count`,
  ADD COLUMN `output_vars_json` JSON NULL COMMENT '出参定义：[{varCode,varType,description,sortNo}]' AFTER `input_vars_json`;

-- ---------------------------------------------------------------------------
-- 2) 回填入参：feature_input_var → feature.input_vars_json
--    · required / enabled 输出为真正的 JSON 布尔值（不是 1/0），前端按 !== false 判断
--    · defaultValue 为 NULL 时输出 null
-- ---------------------------------------------------------------------------
UPDATE `feature` f
JOIN (
    SELECT v.feature_id,
           CAST(CONCAT('[', GROUP_CONCAT(
               JSON_OBJECT(
                   'varCode',      v.var_code,
                   'varType',      v.var_type,
                   'source',       v.source,
                   'required',     CAST(IF(v.required = 1, 'true', 'false') AS JSON),
                   'defaultValue', v.default_value,
                   'enabled',      CAST(IF(v.enabled = 1, 'true', 'false') AS JSON)
               ) ORDER BY v.id SEPARATOR ','
           ), ']') AS JSON) AS js
    FROM `feature_input_var` v
    GROUP BY v.feature_id
) t ON t.feature_id = f.id
SET f.input_vars_json = t.js;

-- ---------------------------------------------------------------------------
-- 3) 回填出参：feature_output_var → feature.output_vars_json
-- ---------------------------------------------------------------------------
UPDATE `feature` f
JOIN (
    SELECT o.feature_id,
           CAST(CONCAT('[', GROUP_CONCAT(
               JSON_OBJECT(
                   'varCode',     o.var_code,
                   'varType',     o.var_type,
                   'description', o.description,
                   'sortNo',      o.sort_no
               ) ORDER BY o.sort_no, o.id SEPARATOR ','
           ), ']') AS JSON) AS js
    FROM `feature_output_var` o
    GROUP BY o.feature_id
) t ON t.feature_id = f.id
SET f.output_vars_json = t.js;

-- ---------------------------------------------------------------------------
-- 4) 删除两张子表
--
--  注意：input_count / output_count 两个计数列**故意不动** —— 它们迁移前就存在
--  （个别特征本就与子表记录不一致），保持原值才能让统计口径与迁移前完全一致；
--  后续保存时由 FeatureService 按 JSON 内容重新对齐。
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS `feature_input_var`;
DROP TABLE IF EXISTS `feature_output_var`;
