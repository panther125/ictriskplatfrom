-- ============================================================================
-- 风控策略配置平台 (Risk Strategy Configuration & Execution Platform)
-- MySQL 数据库初始化脚本
-- 字符集: utf8mb4  引擎: InnoDB
-- 对应 UI 设计稿第 2~9 页：总览/视图/特征/策略编排/单笔试算/交叉决策树/版本灰度/运行监控
-- ============================================================================

CREATE DATABASE IF NOT EXISTS `risk_platform`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
USE `risk_platform`;

-- ---------------------------------------------------------------------------
-- 1. 用户 / 权限
-- ---------------------------------------------------------------------------
CREATE TABLE `app_user` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `username`    VARCHAR(64)  NOT NULL COMMENT '登录名',
  `display_name`VARCHAR(64)  NOT NULL COMMENT '显示名',
  `role`        VARCHAR(32)  NOT NULL DEFAULT 'CONFIG_OPERATOR'
                COMMENT 'CONFIG_OPERATOR / RISK_APPROVER / ADMIN / SYSTEM',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_username` (`username`)
) ENGINE=InnoDB COMMENT='平台用户';

-- ---------------------------------------------------------------------------
-- 2. 视图（数据源取数）
-- ---------------------------------------------------------------------------
CREATE TABLE `data_source_view` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `code`          VARCHAR(128) NOT NULL COMMENT '视图编码，如 risk_user_info_view',
  `name`          VARCHAR(128) NOT NULL COMMENT '视图名称',
  `description`   VARCHAR(512) NULL,
  `source_type`   VARCHAR(16)  NOT NULL COMMENT 'MySQL / CSV / HTTP / PMML',
  `read_mode`     VARCHAR(16)  NOT NULL COMMENT 'SINGLE / LIST / SCALAR',
  `version`       VARCHAR(16)  NOT NULL COMMENT '版本，如 v3',
  `status`        VARCHAR(16)  NOT NULL DEFAULT 'DRAFT'
                COMMENT 'DRAFT草稿 / PUBLISHED已发布 / CIRCUIT_BREAKER熔断',
  `owner`         VARCHAR(64)  NULL,
  `business_domain` VARCHAR(64) NULL COMMENT '业务域，如 信贷',
  `degrade_chain` VARCHAR(256) NULL COMMENT '降级链说明',
  `script_content` LONGTEXT    NULL COMMENT '取数脚本/配置：MySQL=SQL，HTTP=配置文本(入参用 $name 占位)；CSV/PMML=配置 JSON（内嵌文件内容 content，最大 20MB，不依赖服务器文件）',
  `input_vars_json`  TEXT     NULL COMMENT '入参定义 JSON 数组（脚本 $ 占位符解析结果）',
  `output_fields_json` TEXT   NULL COMMENT '出参字段 JSON 数组',
  `ref_count`     INT          NOT NULL DEFAULT 0 COMMENT '被引用数(特征+策略)',
  `field_count`   INT          NOT NULL DEFAULT 0,
  `pii_count`     INT          NOT NULL DEFAULT 0 COMMENT 'PII级字段数',
  `success_rate`  DECIMAL(6,3) NULL COMMENT '取数成功率 %',
  `latency_ms`    DECIMAL(8,2) NULL,
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       BIGINT       NOT NULL DEFAULT 0
                  COMMENT '逻辑删除：0=有效；下线时写入该行 id，保证 (code,deleted) 唯一',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_view_code_del` (`code`,`deleted`)
) ENGINE=InnoDB COMMENT='数据源视图';

-- ---------------------------------------------------------------------------
-- 3. 特征（口径与加工）
-- ---------------------------------------------------------------------------
CREATE TABLE `feature` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `code`          VARCHAR(128) NOT NULL COMMENT '如 feat.user_overdue_cnt',
  `name`          VARCHAR(128) NOT NULL,
  `feature_type`  VARCHAR(16)  NOT NULL COMMENT 'BASE基础 / DERIVED衍生',
  `compute_type`  VARCHAR(16)  NULL COMMENT 'SQL / Java / Python / OP可视化算子',
  `data_type`     VARCHAR(16)  NOT NULL COMMENT 'BIGINT / STRING / DOUBLE',
  `status`        VARCHAR(16)  NOT NULL DEFAULT 'STABLE'
                COMMENT 'STABLE稳定 / EXPERIMENT实验 / SURGE突增 / DEPRECATED已弃用',
  `business_desc` VARCHAR(1024) NULL COMMENT '业务口径(必填≥10字)',
  `sql_expr`      TEXT         NULL COMMENT 'SQL 加工逻辑',
  `view_id`       BIGINT       NULL COMMENT '【基础特征】绑定的视图 id，1:1 引用该视图的一个出参',
  `view_code`     VARCHAR(128) NULL COMMENT '【基础特征】绑定视图编码（创建时快照，便于血缘与失效检测）',
  `view_field`    VARCHAR(128) NULL COMMENT '【基础特征】引用的视图出参字段名，即特征输出值',
  `expr`          TEXT         NULL COMMENT '【衍生特征】加工表达式，用 @@base_feature_code 引用基础特征',
  `missing_policy`VARCHAR(64)  NULL COMMENT '缺失策略',
  `timeout_ms`    INT          NULL,
  `ref_count`     INT          NOT NULL DEFAULT 0 COMMENT '被策略/衍生特征引用数',
  `empty_rate`    DECIMAL(6,3) NULL COMMENT '空值率 %',
  `empty_base`    DECIMAL(6,3) NULL COMMENT '基线空值率 %',
  `owner`         VARCHAR(64)  NULL,
  `input_count`   INT          NULL COMMENT '入参数量',
  `output_count`  INT          NULL COMMENT '出参数量',
  `input_vars_json`  JSON      NULL COMMENT '入参定义：[{varCode,varType,source,required,defaultValue,enabled}]（含各入参默认测试值）',
  `output_vars_json` JSON      NULL COMMENT '出参定义：[{varCode,varType,description,sortNo}]',
  `verify_status` VARCHAR(16)  NOT NULL DEFAULT 'UNVERIFIED'
                  COMMENT 'VERIFIED / WARNING / ERROR / UNVERIFIED',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=有效；下线时写入 id',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_feature_code` (`code`)
) ENGINE=InnoDB COMMENT='特征';

CREATE TABLE `feature_test_case` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `feature_id`  BIGINT       NOT NULL,
  `case_name`   VARCHAR(128) NOT NULL,
  `inputs_json` TEXT         NULL COMMENT '输入变量 JSON',
  `expected`    VARCHAR(64)  NULL,
  `result`      VARCHAR(16)  NULL COMMENT 'PASS / FAIL',
  PRIMARY KEY (`id`),
  KEY `idx_fcase_feature` (`feature_id`)
) ENGINE=InnoDB COMMENT='特征测试用例';

-- ---------------------------------------------------------------------------
-- 4. 策略 与 版本/Bundle
-- ---------------------------------------------------------------------------
CREATE TABLE `strategy` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `code`        VARCHAR(128) NOT NULL COMMENT '如 loan_apply',
  `name`        VARCHAR(128) NOT NULL COMMENT '还款能力准入策略',
  `category`    VARCHAR(64)  NULL COMMENT '决策中心',
  `description` VARCHAR(512) NULL,
  `owner`       VARCHAR(64)  NULL,
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_strategy_code` (`code`)
) ENGINE=InnoDB COMMENT='策略';

CREATE TABLE `strategy_version` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT,
  `strategy_id`    BIGINT       NOT NULL,
  `version_no`     VARCHAR(16)  NOT NULL COMMENT 'v11 / v12',
  `rev`            INT          NOT NULL DEFAULT 0 COMMENT '草稿修订 rev38',
  `status`         VARCHAR(16)  NOT NULL DEFAULT 'DRAFT'
                 COMMENT 'DRAFT / PENDING_REVIEW / GRAYSCALE / PUBLISHED / ROLLED_BACK',
  `state_label`    VARCHAR(32)  NULL COMMENT 'UI展示：生产运行中 / 候选待审批 / v12草稿',
  `bundle`         VARCHAR(128) NULL COMMENT '不可变快照 sb_v12_r38',
  `change_count`   INT          NULL,
  `grayscale_pct`  INT          NULL COMMENT '灰度放量 %',
  `submitted_by`   VARCHAR(64)  NULL,
  `published_by`   VARCHAR(64)  NULL,
  `published_at`   DATETIME     NULL,
  `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_sv_strategy` (`strategy_id`)
) ENGINE=InnoDB COMMENT='策略版本';

-- 决策节点（流程图画布）
CREATE TABLE `decision_node` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `version_id`   BIGINT       NOT NULL,
  `node_id`      VARCHAR(32)  NOT NULL COMMENT 'n_001 / n_005',
  `node_type`    VARCHAR(16)  NOT NULL
               COMMENT 'START / DATA / FEATURE / BRANCH / DECISION / TREE / CROSS / ACTION / END',
  `title`        VARCHAR(128) NOT NULL,
  `x`            INT          NOT NULL DEFAULT 0,
  `y`            INT          NOT NULL DEFAULT 0,
  `condition_expr` TEXT        NULL COMMENT '条件表达式',
  `conclusion`   VARCHAR(16)  NULL COMMENT '通过PASS / 人工审核REVIEW / 拒绝REJECT / 挑战验证CHALLENGE',
  `reason_code`  VARCHAR(64)  NULL,
  `priority`     INT          NULL,
  `ref_vars_json`TEXT         NULL COMMENT '引用变量 JSON',
  `params_json`  TEXT         NULL,
  PRIMARY KEY (`id`),
  KEY `idx_node_version` (`version_id`)
) ENGINE=InnoDB COMMENT='决策节点';

CREATE TABLE `node_edge` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `version_id`  BIGINT       NOT NULL,
  `from_node`   VARCHAR(32)  NOT NULL,
  `to_node`     VARCHAR(32)  NOT NULL,
  `label`       VARCHAR(64)  NULL COMMENT '条件①高风险 / 条件②其他情况',
  `from_port`   VARCHAR(32)  NULL,
  `to_port`     VARCHAR(32)  NULL,
  PRIMARY KEY (`id`),
  KEY `idx_edge_version` (`version_id`)
) ENGINE=InnoDB COMMENT='节点连线';

-- ---------------------------------------------------------------------------
-- 5. 交叉决策树
-- ---------------------------------------------------------------------------
CREATE TABLE `cross_tree` (
  `id`               BIGINT       NOT NULL AUTO_INCREMENT,
  `strategy_id`      BIGINT       NOT NULL,
  `name`             VARCHAR(128) NOT NULL COMMENT '渠道×客群分档',
  `version`          VARCHAR(16)  NULL,
  `status`           VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
  `default_conclusion` VARCHAR(16) NULL COMMENT '默认结论(未命中格子)',
  `coverage`         VARCHAR(32)  NULL COMMENT '15 / 20 · 75%',
  `max_dimensions`   INT          NOT NULL DEFAULT 4,
  `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB COMMENT='交叉决策树';

CREATE TABLE `cross_tree_dimension` (
  `id`         BIGINT      NOT NULL AUTO_INCREMENT,
  `tree_id`    BIGINT      NOT NULL,
  `dim_no`     VARCHAR(8)  NOT NULL COMMENT 'D1 / D2 / D3 / D4',
  `dim_name`   VARCHAR(64) NOT NULL COMMENT '申请渠道 / 客户等级 ...',
  `value_count`INT         NULL COMMENT '4值 / 5值 / 6档',
  `expr`       VARCHAR(256) NULL COMMENT '维度表达式，如 param.channel',
  `bins_json`  TEXT         NULL COMMENT '分箱值 JSON 数组',
  PRIMARY KEY (`id`),
  KEY `idx_dim_tree` (`tree_id`)
) ENGINE=InnoDB COMMENT='交叉树维度';

CREATE TABLE `cross_tree_cell` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT,
  `tree_id`     BIGINT      NOT NULL,
  `slice_name`  VARCHAR(32) NOT NULL COMMENT '切片 APP_IOS / H5 / API',
  `row_key`     VARCHAR(64) NOT NULL COMMENT '客群·白金PLATINUM',
  `col_key`     VARCHAR(64) NOT NULL COMMENT '风险档 A·优 0-60',
  `conclusion`  VARCHAR(16) NOT NULL,
  `score`       INT         NULL,
  `remark`      VARCHAR(128) NULL COMMENT '如 引用策略已下线',
  PRIMARY KEY (`id`),
  KEY `idx_cell_tree` (`tree_id`)
) ENGINE=InnoDB COMMENT='决策矩阵单元格';

-- ---------------------------------------------------------------------------
-- 6. 试算 / 决策日志
-- ---------------------------------------------------------------------------
CREATE TABLE `trial_record` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT,
  `trace_id`    VARCHAR(64) NOT NULL COMMENT 'tr_9f2c41',
  `strategy_id` BIGINT      NULL,
  `version`     VARCHAR(16) NULL,
  `input_json`  TEXT        NULL COMMENT '输入参数 JSON',
  `conclusion`  VARCHAR(16) NULL,
  `reason_code` VARCHAR(64) NULL,
  `hit_rule`    VARCHAR(64) NULL,
  `elapsed_ms`  DECIMAL(10,2) NULL,
  `degraded`    TINYINT     NOT NULL DEFAULT 0,
  `env`         VARCHAR(16)  NOT NULL DEFAULT 'PROD_SHADOW' COMMENT 'PROD_SHADOW / UAT / PROD',
  `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_trial_trace` (`trace_id`)
) ENGINE=InnoDB COMMENT='单笔试算记录';

CREATE TABLE `trial_execution_step` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT,
  `trial_id`    BIGINT      NOT NULL,
  `step_no`     INT         NOT NULL,
  `step_title`  VARCHAR(128) NOT NULL COMMENT 'START·事件接单 ...',
  `elapsed_ms`  DECIMAL(8,2) NULL,
  PRIMARY KEY (`id`),
  KEY `idx_step_trial` (`trial_id`)
) ENGINE=InnoDB COMMENT='试算执行路径步骤';

-- ---------------------------------------------------------------------------
-- 7. 发布与灰度 / 审批
-- ---------------------------------------------------------------------------
CREATE TABLE `grayscale_rule` (
  `id`             BIGINT      NOT NULL AUTO_INCREMENT,
  `version_id`     BIGINT      NOT NULL,
  `mode`           VARCHAR(16) NOT NULL DEFAULT 'PERCENT' COMMENT '切流方式：RANDOM 随机切流 / PERCENT 百分比切流 / WHITELIST 白名单切流',
  `traffic_pct`    INT         NOT NULL DEFAULT 0,
  `whitelist_users`INT         NOT NULL DEFAULT 0,
  `channel`        VARCHAR(64) NULL COMMENT 'APP_IOS',
  `user_tag`       VARCHAR(64) NULL COMMENT 'VIP',
  `status`         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  PRIMARY KEY (`id`),
  KEY `idx_gray_version` (`version_id`)
) ENGINE=InnoDB COMMENT='灰度规则';

CREATE TABLE `release_record` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `title`        VARCHAR(256) NOT NULL COMMENT '变更摘要',
  `scope_type`   VARCHAR(16)  NOT NULL COMMENT 'STRATEGY / VIEW / FEATURE',
  `operator`     VARCHAR(64)  NULL,
  `operator_type`VARCHAR(16)  NULL COMMENT 'USER / SYSTEM / AUTO_GRAYSCALE',
  `occurred_at`  DATETIME     NOT NULL,
  `detail`       VARCHAR(256) NULL,
  PRIMARY KEY (`id`),
  KEY `idx_release_time` (`occurred_at`)
) ENGINE=InnoDB COMMENT='最近发布与变更(审计留痕)';

-- ---------------------------------------------------------------------------
-- 8. 运行监控 / 健康度 / 规则命中
-- ---------------------------------------------------------------------------
CREATE TABLE `health_status` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT,
  `target_type` VARCHAR(16) NOT NULL COMMENT 'VIEW / FEATURE',
  `target_code` VARCHAR(128) NOT NULL,
  `target_name` VARCHAR(128) NULL,
  `status`      VARCHAR(16) NOT NULL COMMENT 'NORMAL / CIRCUIT_BREAKER / SURGE / DRIFT',
  `metric`      VARCHAR(128) NULL COMMENT '空值率/成功率/P99',
  `detail`      VARCHAR(256) NULL,
  `updated_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_health_type` (`target_type`)
) ENGINE=InnoDB COMMENT='视图与特征健康度';

CREATE TABLE `rule_hit_stat` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT,
  `rule_code`   VARCHAR(32) NOT NULL COMMENT 'R007',
  `rule_name`   VARCHAR(128) NULL,
  `hit_count`   INT         NOT NULL,
  `hit_pct`     DECIMAL(5,2) NOT NULL COMMENT '命中占比 %',
  `period`      VARCHAR(32) NULL COMMENT '近24小时 / 全部策略',
  `rank`        INT         NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB COMMENT='规则命中 TOP';

CREATE TABLE `monitor_metric` (
  `id`         BIGINT      NOT NULL AUTO_INCREMENT,
  `metric_key` VARCHAR(64) NOT NULL COMMENT 'decision_count / p99_ms / fallback_pct / degrade_rate',
  `metric_name`VARCHAR(64) NULL,
  `value`      DECIMAL(16,2) NOT NULL,
  `unit`       VARCHAR(16) NULL,
  `comparison` VARCHAR(128) NULL COMMENT '较昨日+6.2%',
  `threshold`  VARCHAR(128) NULL COMMENT '目标≤200ms',
  `state`      VARCHAR(16) NULL COMMENT 'NORMAL / WARN / BREAK',
  `snapshot_at`DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB COMMENT='运行监控指标卡';

CREATE TABLE `p99_trend` (
  `id`          BIGINT   NOT NULL AUTO_INCREMENT,
  `ts`          DATETIME NOT NULL COMMENT '每10分钟',
  `value_ms`    DECIMAL(8,2) NOT NULL,
  `slo_ms`      INT      NOT NULL DEFAULT 200,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB COMMENT='P99耗时趋势(近24h)';

CREATE TABLE `audit_log` (
  `id`         BIGINT      NOT NULL AUTO_INCREMENT,
  `action`     VARCHAR(64) NOT NULL,
  `target_type`VARCHAR(32) NULL,
  `target`     VARCHAR(128) NULL,
  `operator`   VARCHAR(64) NULL,
  `detail`     VARCHAR(512) NULL,
  `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB COMMENT='审计全量留痕';

-- ============================================================================
-- 种子数据
-- ============================================================================

INSERT INTO `app_user` (`username`,`display_name`,`role`) VALUES
 ('wangmin','王敏','CONFIG_OPERATOR'),
 ('zhangwei','张伟','RISK_APPROVER'),
 ('lizhe','李哲','CONFIG_OPERATOR'),
 ('system','系统','SYSTEM');

-- 视图
INSERT INTO `data_source_view`
 (`code`,`name`,`description`,`source_type`,`read_mode`,`version`,`status`,`owner`,`business_domain`,`degrade_chain`,`input_vars_json`,`output_fields_json`,`ref_count`,`field_count`,`pii_count`,`success_rate`,`latency_ms`) VALUES
 ('risk_user_info_view','用户风险信息表','用户基础风险信息','MySQL','SINGLE','v3','PUBLISHED','王敏','信贷','陈旧缓存(1h)→字段默认值→中断并返回兜底结论',
  '[{"varCode":"user_id","varType":"STRING","required":true},{"varCode":"mobile","varType":"STRING","required":false}]',
  '[{"fieldName":"user_id","fieldType":"BIGINT","category":"ID","piiLevel":"NONE","isPrimary":1,"sort":1},{"fieldName":"mobile","fieldType":"STRING","category":"PHONE","piiLevel":"PII","masking":"mask_phone","isPrimary":0,"sort":2},{"fieldName":"overdue_cnt","fieldType":"BIGINT","category":"COUNT","piiLevel":"NONE","isPrimary":0,"sort":3},{"fieldName":"credit_limit","fieldType":"DECIMAL","category":"AMOUNT","piiLevel":"NONE","isPrimary":0,"sort":4},{"fieldName":"updated_at","fieldType":"DATETIME","category":"TIME","piiLevel":"NONE","isPrimary":0,"sort":5}]',
  16,8,3,99.6,32.40),
 ('loan_order_list_view','借款申请流水(多行)','借款申请流水','MySQL','LIST','v2','PUBLISHED','王敏','信贷','陈旧缓存(1h)→空列表→中断并返回兜底结论',
  '[{"varCode":"user_id","varType":"STRING","required":true}]',
  '[{"fieldName":"order_id","fieldType":"BIGINT","category":"ID","piiLevel":"NONE","isPrimary":1,"sort":1},{"fieldName":"apply_amount","fieldType":"DECIMAL","category":"AMOUNT","piiLevel":"NONE","isPrimary":0,"sort":2},{"fieldName":"apply_dt","fieldType":"DATETIME","category":"TIME","piiLevel":"NONE","isPrimary":0,"sort":3},{"fieldName":"status","fieldType":"STRING","category":"STATUS","piiLevel":"NONE","isPrimary":0,"sort":4},{"fieldName":"term","fieldType":"INTEGER","category":"TERM","piiLevel":"NONE","isPrimary":0,"sort":5},{"fieldName":"rate","fieldType":"DECIMAL","category":"RATE","piiLevel":"NONE","isPrimary":0,"sort":6}]',
  0,6,1,99.4,21.70),
 ('ext_blacklist_view','外部黑名单(征信三方)','外部黑名单命中','HTTP','SINGLE','v5','CIRCUIT_BREAKER','系统','征信','熔断打开→陈旧缓存→返回兜底结论',
  '[{"varCode":"id_no","varType":"STRING","required":true}]',
  '[{"fieldName":"hit","fieldType":"BOOLEAN","category":"FLAG","piiLevel":"NONE","isPrimary":0,"sort":1},{"fieldName":"level","fieldType":"STRING","category":"LEVEL","piiLevel":"NONE","isPrimary":0,"sort":2},{"fieldName":"source","fieldType":"STRING","category":"SOURCE","piiLevel":"NONE","isPrimary":0,"sort":3},{"fieldName":"updated_at","fieldType":"DATETIME","category":"TIME","piiLevel":"NONE","isPrimary":0,"sort":4}]',
  0,4,2,32.0,800.00),
 ('pmml_risk_score_view','风控模型评分(PMML)','风控模型评分','PMML','SCALAR','v7','DRAFT','李哲','信贷','超时→默认分600',
  '[{"varCode":"user_id","varType":"STRING","required":true}]',
  '[{"fieldName":"risk_score","fieldType":"DECIMAL","category":"SCORE","piiLevel":"NONE","isPrimary":0,"sort":1},{"fieldName":"model_version","fieldType":"STRING","category":"META","piiLevel":"NONE","isPrimary":0,"sort":2}]',
  0,2,0,99.1,18.00),
 ('user_credit_report_view','人行征信报告摘要','人行征信摘要','HTTP','SINGLE','v9','PUBLISHED','张伟','征信','陈旧缓存(1h)→字段默认值→中断并返回兜底结论',
  '[{"varCode":"id_no","varType":"STRING","required":true},{"varCode":"name","varType":"STRING","required":true}]',
  '[{"fieldName":"credit_score","fieldType":"INTEGER","category":"SCORE","piiLevel":"NONE","isPrimary":0,"sort":1},{"fieldName":"total_debt","fieldType":"DECIMAL","category":"AMOUNT","piiLevel":"NONE","isPrimary":0,"sort":2},{"fieldName":"overdue_records","fieldType":"INTEGER","category":"COUNT","piiLevel":"NONE","isPrimary":0,"sort":3},{"fieldName":"credit_cards","fieldType":"INTEGER","category":"COUNT","piiLevel":"NONE","isPrimary":0,"sort":4},{"fieldName":"housing_loan","fieldType":"BOOLEAN","category":"FLAG","piiLevel":"NONE","isPrimary":0,"sort":5},{"fieldName":"report_date","fieldType":"DATETIME","category":"TIME","piiLevel":"NONE","isPrimary":0,"sort":6},{"fieldName":"id_no_masked","fieldType":"STRING","category":"ID","piiLevel":"PII","masking":"mask_idcard","isPrimary":0,"sort":7},{"fieldName":"name_masked","fieldType":"STRING","category":"NAME","piiLevel":"PII","masking":"mask_name","isPrimary":0,"sort":8}]',
  0,8,3,98.7,45.00);

-- 特征
INSERT INTO `feature`
 (`code`,`name`,`feature_type`,`compute_type`,`data_type`,`status`,`business_desc`,`sql_expr`,`missing_policy`,`timeout_ms`,`ref_count`,`empty_rate`,`empty_base`,`owner`,`input_count`,`output_count`,`verify_status`) VALUES
 ('feat.user_overdue_cnt','近90天逾期次数','BASE','SQL','BIGINT','SURGE','近90天逾期次数，含已结清订单，不含宽限期记录。','SELECT overdue_cnt FROM t_input','ERROR_CONTINUE·默认0+原因码',30,6,12.4,3.1,'王敏',2,1,'VERIFIED'),
 ('feat.user_overdue_level','逾期等级','DERIVED','SQL','STRING','STABLE','近90天内该用户发生逾期的最高等级：H（≥3次）/M（1-2次）/L（0次）。含已结清订单，不含宽限期记录。当前被4个策略、1个衍生特征引用。','SELECT CASE WHEN user_overdue_cnt >= 3 THEN \'H\' WHEN user_overdue_cnt >= 1 THEN \'M\' ELSE \'L\' END AS result FROM t_input','ERROR_CONTINUE·默认0+原因码',50,4,0.8,0.5,'王敏',3,1,'VERIFIED'),
 ('feat.user_apply_cnt_7d','近7天申请次数','DERIVED','SQL','BIGINT','STABLE','近7天借款申请次数统计。','SELECT COUNT(*) FROM t_input WHERE apply_dt >= window_start','ERROR_CONTINUE·默认0+原因码',50,2,0.2,0.1,'王敏',2,1,'WARNING'),
 ('feat.device_risk_score','设备风险分','DERIVED','Java','DOUBLE','EXPERIMENT','设备风险评分，由Java插件计算，处于实验阶段。','// Java plugin: DeviceRiskScorer','ERROR_DEFAULT·默认600',60,1,4.6,1.8,'李哲',1,1,'UNVERIFIED');

-- 特征入参 / 出参定义：直接存 feature 表的 JSON 列（不再单独建表）
-- 入参里的 defaultValue 即该入参的默认测试值
UPDATE `feature` SET `input_vars_json` = JSON_ARRAY(
  JSON_OBJECT('varCode','feat.user_overdue_cnt','varType','BIGINT','source','FEATURE',
              'required',CAST('true' AS JSON),'defaultValue',NULL,'enabled',CAST('true' AS JSON)),
  JSON_OBJECT('varCode','window_start','varType','DATETIME','source','BUILTIN_TIME',
              'required',CAST('true' AS JSON),'defaultValue',NULL,'enabled',CAST('true' AS JSON))
) WHERE `id` = 2;

UPDATE `feature` SET `input_vars_json` = JSON_ARRAY(
  JSON_OBJECT('varCode','view.loan_order_list.apply_dt','varType','DATETIME','source','VIEW',
              'required',CAST('true' AS JSON),'defaultValue',NULL,'enabled',CAST('true' AS JSON)),
  JSON_OBJECT('varCode','window_start','varType','DATETIME','source','BUILTIN_TIME',
              'required',CAST('true' AS JSON),'defaultValue',NULL,'enabled',CAST('true' AS JSON))
) WHERE `id` = 3;

UPDATE `feature` SET `output_vars_json` = JSON_ARRAY(
  JSON_OBJECT('varCode','result','varType',`data_type`,'description','表达式返回值','sortNo',0)
) WHERE `id` IN (2,3);

INSERT INTO `feature_test_case` (`feature_id`,`case_name`,`inputs_json`,`expected`,`result`) VALUES
 (2,'边界：0次→L','{"user_overdue_cnt":0}','L','PASS'),
 (2,'边界：1次→M','{"user_overdue_cnt":1}','M','PASS'),
 (2,'边界：3次→H','{"user_overdue_cnt":3}','H','PASS'),
 (2,'空值兜底','{"user_overdue_cnt":null}','L','PASS');

-- 策略 + 版本
INSERT INTO `strategy` (`code`,`name`,`category`,`description`,`owner`) VALUES
 ('loan_apply','还款能力准入策略','决策中心','借款申请还款能力准入决策','王敏'),
 ('credit_check','信用检查策略','决策中心','信用综合检查','王敏');

INSERT INTO `strategy_version`
 (`strategy_id`,`version_no`,`rev`,`status`,`state_label`,`bundle`,`change_count`,`grayscale_pct`,`submitted_by`,`published_by`,`published_at`) VALUES
 (1,'v11',36,'PUBLISHED','生产运行中','sb_v11_r36',NULL,100,'王敏','王敏','2026-10-08 10:00:00'),
 (1,'v12',38,'PENDING_REVIEW','候选·待审批','sb_v12_r38',6,10,'王敏',NULL,NULL),
 (2,'v8',20,'GRAYSCALE','灰度20%','sb_v8_r20',4,20,'王敏','系统','2026-10-11 08:00:00');

-- 策略 v12 节点图
INSERT INTO `decision_node`
 (`version_id`,`node_id`,`node_type`,`title`,`x`,`y`,`condition_expr`,`conclusion`,`reason_code`,`priority`,`ref_vars_json`,`params_json`) VALUES
 (2,'n_001','START','开始 START',0,0,'event = LOAN_APPLY',NULL,NULL,NULL,'["param.user_id","param.apply_amount","param.channel"]','{"inputs":3}'),
 (2,'n_002','DATA','取数·用户信息',0,1,'view.risk_user_info·12变量',NULL,NULL,NULL,'["view.risk_user_info"]','{"source":"risk_user_info_view","latency":32.4}'),
 (2,'n_003','FEATURE','特征·逾期等级',0,2,'user_overdue_level = H',NULL,NULL,NULL,'["feat.user_overdue_level"]','{"latency":2.1}'),
 (2,'n_004','BRANCH','分支·风险分档',0,3,NULL,NULL,NULL,NULL,NULL,NULL),
 (2,'n_005','DECISION','决策·逾期准入',-1,4,'feat.user_overdue_level == \'H\' and view.risk_user_info.overdue_cnt >= 3','REJECT','RISK_OVERDUE_HIGH',20,'["feat.user_overdue_level","view.risk_user_info.overdue_cnt"]','{"rule":"R007"}'),
 (2,'n_006','CROSS','交叉决策树',1,4,NULL,NULL,NULL,NULL,'["param.channel","param.risk_score"]','{"shape":"4x5","hit":"B2"}'),
 (2,'n_007','ACTION','动作·TERMINATE',0,5,NULL,NULL,NULL,NULL,NULL,'{"shortcut":true}'),
 (2,'n_008','END','结束·组装输出',0,6,NULL,NULL,NULL,NULL,'["conclusion","trace"]','{"latency":1.2}');

INSERT INTO `node_edge` (`version_id`,`from_node`,`to_node`,`label`) VALUES
 (2,'n_001','n_002',NULL),
 (2,'n_002','n_003',NULL),
 (2,'n_003','n_004',NULL),
 (2,'n_004','n_005','条件①高风险'),
 (2,'n_004','n_006','条件②其他情况'),
 (2,'n_005','n_007',NULL),
 (2,'n_006','n_007',NULL),
 (2,'n_007','n_008',NULL);

-- 交叉决策树
INSERT INTO `cross_tree` (`strategy_id`,`name`,`version`,`status`,`default_conclusion`,`coverage`,`max_dimensions`) VALUES
 (1,'渠道×客群分档','v3','DRAFT','REVIEW','15 / 20 · 75%',4);

INSERT INTO `cross_tree_dimension` (`tree_id`,`dim_no`,`dim_name`,`value_count`,`expr`,`bins_json`) VALUES
 (1,'D1','申请渠道',4,'param.channel','["APP_IOS","H5","API","OTHER"]'),
 (1,'D2','客户等级',5,'view.risk_user_info.credit_level','["PLATINUM","GOLD","NORMAL","SUBPRIME","OTHER"]'),
 (1,'D3','申请金额',6,'param.apply_amount','["[0,1万)","[1万,5万)","[5万,10万)","[10万,20万)","[20万,50万)","[50万,∞)"]'),
 (1,'D4','风险分档',4,'param.risk_score','["A·优 0-60","B·良 60-70","C·关注 70-80","D·高风险 80+"]');

-- 切片 APP_IOS 的决策矩阵（行=客群，列=风险档）
INSERT INTO `cross_tree_cell` (`tree_id`,`slice_name`,`row_key`,`col_key`,`conclusion`,`score`,`remark`) VALUES
 (1,'APP_IOS','白金·PLATINUM','A·优 0-60','PASS',90,NULL),
 (1,'APP_IOS','白金·PLATINUM','B·良 60-70','PASS',85,NULL),
 (1,'APP_IOS','白金·PLATINUM','C·关注 70-80','REVIEW',NULL,NULL),
 (1,'APP_IOS','白金·PLATINUM','D·高风险 80+','REJECT',NULL,'高风险'),
 (1,'APP_IOS','黄金·GOLD','A·优 0-60','PASS',80,NULL),
 (1,'APP_IOS','黄金·GOLD','B·良 60-70','REVIEW',NULL,NULL),
 (1,'APP_IOS','黄金·GOLD','C·关注 70-80','REVIEW',NULL,'引用策略已下线'),
 (1,'APP_IOS','黄金·GOLD','D·高风险 80+','REJECT',NULL,'高风险'),
 (1,'APP_IOS','普通·NORMAL','A·优 0-60','REVIEW',NULL,NULL),
 (1,'APP_IOS','普通·NORMAL','B·良 60-70','REVIEW',NULL,NULL),
 (1,'APP_IOS','普通·NORMAL','C·关注 70-80','REJECT',NULL,'中风险'),
 (1,'APP_IOS','普通·NORMAL','D·高风险 80+','REJECT',NULL,'高风险'),
 (1,'APP_IOS','次级·SUBPRIME','A·优 0-60','REJECT',NULL,'高风险'),
 (1,'APP_IOS','次级·SUBPRIME','B·良 60-70','REJECT',NULL,'高风险'),
 (1,'APP_IOS','次级·SUBPRIME','C·关注 70-80','REJECT',NULL,'中风险'),
 (1,'APP_IOS','次级·SUBPRIME','D·高风险 80+','UNCONFIGURED',NULL,'未配置→默认');

-- 试算记录（还款能力准入 v12，U100238）
INSERT INTO `trial_record`
 (`trace_id`,`strategy_id`,`version`,`input_json`,`conclusion`,`reason_code`,`hit_rule`,`elapsed_ms`,`degraded`,`env`) VALUES
 ('tr_9f2c41',1,'v12','{"user_id":"U100238","apply_amount":58000,"channel":"APP_IOS","risk_score":742}','REJECT','RISK_OVERDUE_HIGH','R007 逾期准入',82.0,0,'PROD_SHADOW');

INSERT INTO `trial_execution_step` (`trial_id`,`step_no`,`step_title`,`elapsed_ms`) VALUES
 (1,1,'START·事件接单·event = LOAN_APPLY',0.3),
 (1,2,'取数·用户信息·view.risk_user_info·12变量',32.4),
 (1,3,'取数·订单列表·view.loan_order_list·6变量',21.7),
 (1,4,'特征·逾期等级·feat.user_overdue_level = H',2.1),
 (1,5,'特征·申请次数7d·feat.user_apply_cnt_7d = 2',1.8),
 (1,6,'分支·风险分档→命中条件①高风险',0.4),
 (1,7,'决策·逾期准入→REJECT·RISK_OVERDUE_HIGH',1.2),
 (1,8,'动作·TERMINATE 短路·跳过交叉决策树',0.1),
 (1,9,'结束·组装输出·生成Trace与决策日志',1.2);

-- 灰度规则（v12）
INSERT INTO `grayscale_rule` (`version_id`,`traffic_pct`,`whitelist_users`,`channel`,`user_tag`,`status`) VALUES
 (2,10,50,'APP_IOS','VIP','ACTIVE');

-- 最近发布与变更（审计）
INSERT INTO `release_record` (`title`,`scope_type`,`operator`,`operator_type`,`occurred_at`,`detail`) VALUES
 ('loan_apply_v3 v12发布至 PRE 环境','STRATEGY','王敏','USER','2026-10-11 09:28:00','冻结3视图/12特征'),
 ('credit_check_v2 v8灰度提升至20%','STRATEGY','系统','AUTO_GRAYSCALE','2026-10-11 08:00:00','结论分布偏差0.4pp'),
 ('feat.user_overdue_cnt口径变更','FEATURE','系统','USER','2026-10-11 07:00:00','自动重跑下游6条测试用例'),
 ('view_ext_blacklist_list熔断','VIEW','系统','SYSTEM','2026-10-11 06:00:00','自动切换陈旧缓存并告警'),
 ('user_overdue_level标记为deprecated','FEATURE','李哲','USER','2026-10-10 18:40:00','迁移期30天');

-- 健康度
INSERT INTO `health_status` (`target_type`,`target_code`,`target_name`,`status`,`metric`,`detail`) VALUES
 ('VIEW','view.risk_user_info','risk_user_info_view','NORMAL','0.2% · 32ms','正常'),
 ('VIEW','view.ext_blacklist_list','ext_blacklist_view','CIRCUIT_BREAKER','-','熔断中'),
 ('FEATURE','feat.user_overdue_level','user_overdue_level','NORMAL','0.8% · 2.1ms','正常'),
 ('FEATURE','feat.pmml_risk_score','pmml_risk_score','DRIFT','4.6% · 18ms','分布漂移');

-- 规则命中 TOP（近24小时·全部策略）
INSERT INTO `rule_hit_stat` (`rule_code`,`rule_name`,`hit_count`,`hit_pct`,`period`,`rank`) VALUES
 ('R007','逾期准入',18204,12.40,'近24小时',1),
 ('R012','外部黑名单命中(征信3源)',6915,4.70,'近24小时',2),
 ('R003','设备风险分≥80且非白名单设备',5102,3.50,'近24小时',3),
 ('R021','未参保且年龄<25(实验特征引用)',1408,0.96,'近24小时',4),
 ('R015','高负债',2980,5.10,'全部策略',5);

-- 运行监控指标卡
INSERT INTO `monitor_metric` (`metric_key`,`metric_name`,`value`,`unit`,`comparison`,`threshold`,`state`) VALUES
 ('online_version_count','在线策略版本',128,'个','灰度中2·待审批3','-','NORMAL'),
 ('decision_count','今日在线决策量',2418905,'次','较昨日+6.2%·峰值1,860 QPS','-','NORMAL'),
 ('p99_ms','决策P99耗时',143,'ms','目标≤200ms·引擎自身≤20ms','≤200ms','NORMAL'),
 ('fallback_pct','兜底结论占比',0.32,'%','告警阈值1%·熔断中视图1个','1%','NORMAL'),
 ('degrade_rate','降级率',0.05,'%','阈值0.5%·正常','0.5%','NORMAL'),
 ('today_decision_prev','昨日决策量',2277230,'次','-','-','NORMAL');

-- P99 趋势（近24h，每10分钟，含峰值178ms @10:00）
INSERT INTO `p99_trend` (`ts`,`value_ms`,`slo_ms`) VALUES
 ('2026-10-10 10:00:00',120,200),('2026-10-10 11:00:00',128,200),('2026-10-10 12:00:00',131,200),
 ('2026-10-10 13:00:00',126,200),('2026-10-10 14:00:00',140,200),('2026-10-10 15:00:00',155,200),
 ('2026-10-10 16:00:00',148,200),('2026-10-10 17:00:00',152,200),('2026-10-10 18:00:00',160,200),
 ('2026-10-10 19:00:00',158,200),('2026-10-10 20:00:00',149,200),('2026-10-10 21:00:00',142,200),
 ('2026-10-10 22:00:00',138,200),('2026-10-10 23:00:00',132,200),('2026-10-11 00:00:00',128,200),
 ('2026-10-11 01:00:00',125,200),('2026-10-11 02:00:00',122,200),('2026-10-11 03:00:00',121,200),
 ('2026-10-11 04:00:00',120,200),('2026-10-11 05:00:00',118,200),('2026-10-11 06:00:00',119,200),
 ('2026-10-11 07:00:00',122,200),('2026-10-11 08:00:00',126,200),('2026-10-11 09:00:00',130,200),
 ('2026-10-11 10:00:00',178,200),('2026-10-11 11:00:00',165,200),('2026-10-11 12:00:00',158,200),
 ('2026-10-11 13:00:00',143,200),('2026-10-11 14:00:00',143,200);

-- 审计示例
INSERT INTO `audit_log` (`action`,`target_type`,`target`,`operator`,`detail`) VALUES
 ('RELEASE','STRATEGY','loan_apply_v3','王敏','v12 发布至 PRE 环境，冻结3视图/12特征'),
 ('GRAYSCALE_UP','STRATEGY','credit_check_v2','系统','灰度提升至20%'),
 ('CIRCUIT_BREAKER','VIEW','view_ext_blacklist_list','系统','熔断打开，切换陈旧缓存');

-- 索引收敛（运行监控常用查询）
CREATE INDEX `idx_metric_time` ON `monitor_metric` (`snapshot_at`);
CREATE INDEX `idx_hit_period`  ON `rule_hit_stat` (`period`);

-- ============================================================================
-- 9. Track 链路追踪（决策日志与回放）
--    每次策略调用记录完整链路：trace 头 + 逐节点耗时 + 变量快照
-- ============================================================================
CREATE TABLE `track_record` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT,
  `trace_id`       VARCHAR(64)  NOT NULL COMMENT 'tr_xxxx 全局唯一',
  `strategy_id`    BIGINT       NULL,
  `strategy_name`  VARCHAR(128) NULL COMMENT '还款能力准入策略',
  `version`        VARCHAR(16)  NULL COMMENT 'v12',
  `env`            VARCHAR(16)  NOT NULL DEFAULT 'PROD' COMMENT 'PROD / PROD_SHADOW / UAT',
  `source`         VARCHAR(16)  NOT NULL DEFAULT 'ONLINE' COMMENT 'ONLINE在线 / TRIAL试算 / REPLAY回放',
  `input_json`     TEXT         NULL COMMENT '入参快照',
  `conclusion`     VARCHAR(16)  NULL COMMENT '结论 PASS/REVIEW/REJECT/CHALLENGE/兜底',
  `reason_code`    VARCHAR(64)  NULL,
  `hit_rule`       VARCHAR(128) NULL COMMENT '命中规则/路径',
  `hit_path`       VARCHAR(256) NULL COMMENT '命中路径 n_001→n_002→...',
  `total_elapsed_ms` DECIMAL(10,2) NULL COMMENT '整体耗时',
  `engine_elapsed_ms` DECIMAL(10,2) NULL COMMENT '引擎自身耗时(不含外部取数)',
  `degraded`       TINYINT      NOT NULL DEFAULT 0 COMMENT '是否降级/兜底',
  `total_score`    DECIMAL(8,2) NULL COMMENT '总分',
  `business_key`   VARCHAR(128) NULL COMMENT '业务主键',
  `channel`        VARCHAR(64)  NULL COMMENT '渠道',
  `channel_code`   VARCHAR(32)  NULL COMMENT '渠道码',
  `sampling_reason`VARCHAR(64)  NULL COMMENT '采样原因',
  `degrade_count`  INT          NOT NULL DEFAULT 0 COMMENT '降级次数',
  `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_track_trace` (`trace_id`),
  KEY `idx_track_time`  (`created_at`),
  KEY `idx_track_strategy` (`strategy_id`),
  KEY `idx_track_conclusion` (`conclusion`)
) ENGINE=InnoDB COMMENT='决策调用链路头';

CREATE TABLE `track_step` (
  `id`         BIGINT      NOT NULL AUTO_INCREMENT,
  `track_id`   BIGINT      NOT NULL,
  `step_no`    INT         NOT NULL,
  `node_id`    VARCHAR(32) NULL COMMENT 'n_001 / n_005',
  `node_type`  VARCHAR(16) NULL COMMENT 'START/DATA/FEATURE/BRANCH/DECISION/ACTION/END',
  `category`   VARCHAR(16) NULL COMMENT '取数/特征/决策/动作/引擎',
  `step_title` VARCHAR(160) NULL,
  `elapsed_ms` DECIMAL(8,2) NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tstep_track` (`track_id`)
) ENGINE=InnoDB COMMENT='链路逐节点耗时';

CREATE TABLE `track_var` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `track_id`   BIGINT       NOT NULL,
  `namespace`  VARCHAR(32)  NOT NULL COMMENT 'param.* / view.* / feat.* / node.*',
  `var_name`   VARCHAR(160) NOT NULL,
  `var_value`  VARCHAR(512) NULL,
  `step_no`    INT          NULL COMMENT '该变量在哪个步骤被写入',
  PRIMARY KEY (`id`),
  KEY `idx_tvar_track` (`track_id`)
) ENGINE=InnoDB COMMENT='链路变量快照';

-- 示例：一条完整在线决策调用（还款能力准入 v12 → REJECT）
INSERT INTO `track_record`
 (`trace_id`,`strategy_id`,`strategy_name`,`version`,`env`,`source`,`input_json`,
  `conclusion`,`reason_code`,`hit_rule`,`hit_path`,`total_elapsed_ms`,`engine_elapsed_ms`,`degraded`,`created_at`) VALUES
 ('tr_9f2c41',1,'还款能力准入策略','v12','PROD','ONLINE',
  '{"user_id":"U100238","apply_amount":58000,"channel":"APP_IOS","risk_score":742,"overdue_cnt":3}',
  'REJECT','RISK_OVERDUE_HIGH','R007 逾期准入','n_001→n_002→n_003→n_004→n_005→n_007→n_008',
  82.0,22.3,0,'2026-10-11 09:32:00'),
 ('tr_7a11e9',1,'还款能力准入策略','v12','PROD','ONLINE',
  '{"user_id":"U100521","apply_amount":32000,"channel":"H5","risk_score":601,"overdue_cnt":1}',
  'REVIEW','RISK_SCORE_MEDIUM','交叉决策树 B2','n_001→n_002→n_003→n_004→n_006→n_007→n_008',
  74.3,19.8,0,'2026-10-11 09:40:00'),
 ('tr_c30bd6',1,'还款能力准入策略','v12','PROD','ONLINE',
  '{"user_id":"U100887","apply_amount":120000,"channel":"API","risk_score":820,"overdue_cnt":0}',
  'REJECT','RISK_OVER_LIMIT','R015 高负债','n_001→n_002→n_003→n_004→n_006→n_007→n_008',
  90.1,25.4,0,'2026-10-11 09:47:00');

INSERT INTO `track_step` (`track_id`,`step_no`,`node_id`,`node_type`,`category`,`step_title`,`elapsed_ms`) VALUES
 (1,1,'n_001','START','引擎','START·事件接单·event = LOAN_APPLY',0.3),
 (1,2,'n_002','DATA','取数','取数·用户信息·view.risk_user_info·12变量',32.4),
 (1,3,'n_003','DATA','取数','取数·订单列表·view.loan_order_list·6变量',21.7),
 (1,4,'n_004','FEATURE','特征','特征·逾期等级·feat.user_overdue_level = H',2.1),
 (1,5,'n_004','FEATURE','特征','特征·申请次数7d·feat.user_apply_cnt_7d = 2',1.8),
 (1,6,'n_005','BRANCH','决策','分支·风险分档→命中条件①高风险',0.4),
 (1,7,'n_006','DECISION','决策','决策·逾期准入→REJECT·RISK_OVERDUE_HIGH',1.2),
 (1,8,'n_007','ACTION','动作','动作·TERMINATE 短路·跳过交叉决策树',0.1),
 (1,9,'n_008','END','引擎','结束·组装输出·生成Trace与决策日志',1.2);

INSERT INTO `track_var` (`track_id`,`namespace`,`var_name`,`var_value`,`step_no`) VALUES
 (1,'param.*','param.user_id','U100238',1),
 (1,'param.*','param.apply_amount','58000',1),
 (1,'param.*','param.channel','APP_IOS',1),
 (1,'param.*','param.risk_score','742',1),
 (1,'view.*','view.risk_user_info.overdue_cnt','3',2),
 (1,'view.*','view.risk_user_info.mobile','138****',2),
 (1,'view.*','view.risk_user_info.credit_limit','50000',2),
 (1,'view.*','view.loan_order_list.apply_amount','58000',3),
 (1,'feat.*','feat.user_overdue_level','H',4),
 (1,'feat.*','feat.user_apply_cnt_7d','2',5),
 (1,'node.*','node.n_005.result','REJECT',7),
 (1,'node.*','node.n_005.reasonCode','RISK_OVERDUE_HIGH',7),
 (1,'node.*','node.n_005.hit','R007 逾期准入',7);

CREATE INDEX `idx_tstep_node` ON `track_step` (`track_id`,`step_no`);

-- ============================================================================
-- 10. 回归用例集（试算中心）
-- ============================================================================
CREATE TABLE `trial_case` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT,
  `name`              VARCHAR(128) NOT NULL COMMENT '用例名称',
  `strategy_id`       BIGINT       NULL COMMENT '关联策略',
  `inputs_json`       TEXT         NULL COMMENT '输入参数 JSON',
  `expect_conclusion` VARCHAR(16)  NULL COMMENT '期望结论',
  `tags`              VARCHAR(256) NULL COMMENT '标签',
  `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB COMMENT='回归用例集';

-- ============================================================================
-- 11. Track 采样与留存策略配置
-- ============================================================================
CREATE TABLE `track_sampling_config` (
  `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
  `config_key`         VARCHAR(64)  NOT NULL UNIQUE COMMENT '配置键',
  `ratio_sampling`     VARCHAR(16)  NULL COMMENT '采样比例，如 1%',
  `error_auto_capture` TINYINT      NOT NULL DEFAULT 1 COMMENT '异常自动捕获',
  `slow_threshold_ms`  INT          NULL COMMENT '慢请求阈值 ms',
  `retention`          VARCHAR(128) NULL COMMENT '留存策略描述',
  `updated_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB COMMENT='Track采样与留存配置';

-- ============================================================================
-- 12. 特征审批流
-- ============================================================================
CREATE TABLE `feature_approval` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `feature_id`  BIGINT       NOT NULL,
  `step_no`     INT          NOT NULL,
  `step_name`   VARCHAR(128) NOT NULL,
  `approver`    VARCHAR(64)  NULL,
  `status`      VARCHAR(16)  NOT NULL COMMENT 'DONE / PENDING / QUEUED',
  `remark`      VARCHAR(256) NULL,
  `finished_at` DATETIME     NULL,
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_fappr_feature` (`feature_id`)
) ENGINE=InnoDB COMMENT='特征审批流';

-- ============================================================================
-- 13. 视图降级与缓存配置
-- ============================================================================
CREATE TABLE `view_degrade_config` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT,
  `view_id`         BIGINT       NOT NULL,
  `degrade_mode`    VARCHAR(32)  NOT NULL DEFAULT 'DEFAULT_VALUE'
                    COMMENT 'DEFAULT_VALUE / STALE_CACHE / INTERRUPT',
  `on_fallback`     VARCHAR(32)  NOT NULL DEFAULT 'CONTINUE'
                    COMMENT 'CONTINUE / TERMINATE',
  `cache_l1`        VARCHAR(16)  NULL COMMENT 'L1缓存时长，如 60s',
  `cache_l2`        VARCHAR(16)  NULL COMMENT 'L2缓存时长，如 300s',
  `cb_enabled`      TINYINT      NOT NULL DEFAULT 0 COMMENT '熔断器开关',
  `cb_error_rate`   DECIMAL(5,2) NULL COMMENT '熔断错误率阈值 %',
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_vdc_view` (`view_id`)
) ENGINE=InnoDB COMMENT='视图降级与缓存配置';

-- 默认采样配置种子
INSERT INTO `track_sampling_config` (`config_key`,`ratio_sampling`,`error_auto_capture`,`slow_threshold_ms`,`retention`) VALUES
 ('default','1%',1,200,'热7天+冷对象存储');

-- 视图降级配置种子
INSERT INTO `view_degrade_config` (`view_id`,`degrade_mode`,`on_fallback`,`cache_l1`,`cache_l2`,`cb_enabled`,`cb_error_rate`) VALUES
 (1,'STALE_CACHE','CONTINUE','60s','300s',0,3.20),
 (3,'DEFAULT_VALUE','CONTINUE',NULL,NULL,1,5.00);

-- 回归用例集种子
INSERT INTO `trial_case` (`name`,`strategy_id`,`inputs_json`,`expect_conclusion`,`tags`) VALUES
 ('case_001 线上中额B类',1,'{"user_id":"U100238","apply_amount":58000,"channel":"APP_IOS","risk_score":742,"overdue_cnt":3}','REJECT','核心回归'),
 ('case_002 H5低额A类',1,'{"user_id":"U100521","apply_amount":32000,"channel":"H5","risk_score":601,"overdue_cnt":1}','REVIEW','核心回归'),
 ('case_003 API高额无逾期',1,'{"user_id":"U100887","apply_amount":120000,"channel":"API","risk_score":820,"overdue_cnt":0}','REJECT','边界');

-- ============================================================================
-- 14. 策略编排（决策版本 / 画布节点 / 连线）
--     decision 管理策略对应的编排版本；rule 存每个画布节点（坐标与规则 JSON）；
--     decision_branch 存节点之间的连线（from_port 区分分支节点的多条出边）。
-- ============================================================================
CREATE TABLE IF NOT EXISTS `decision` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `strategy_id` BIGINT       NOT NULL COMMENT '所属策略',
  `version_no`  VARCHAR(16)  NOT NULL COMMENT '版本号，如 v13',
  `status`      VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / PUBLISHED / OFFLINE',
  `description` VARCHAR(512) NULL,
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_decision_strategy_version` (`strategy_id`,`version_no`),
  KEY `idx_decision_strategy` (`strategy_id`)
) ENGINE=InnoDB COMMENT='决策编排版本（每个策略的每个版本一行）';

CREATE TABLE IF NOT EXISTS `rule` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `decision_id` BIGINT       NOT NULL COMMENT '所属决策版本',
  `node_key`    VARCHAR(32)  NOT NULL COMMENT '画布节点键，如 n_001',
  `node_type`   VARCHAR(16)  NOT NULL COMMENT 'START / END / BRANCH / CASE / CROSS / NORMAL',
  `title`       VARCHAR(128) NOT NULL,
  `x`           INT          NOT NULL DEFAULT 0 COMMENT '画布坐标 X',
  `y`           INT          NOT NULL DEFAULT 0 COMMENT '画布坐标 Y',
  `config_json` TEXT         NULL COMMENT '节点规则配置 JSON（分支条件 / case-when 行 / 交叉矩阵 / 规则列表）',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_rule_decision` (`decision_id`)
) ENGINE=InnoDB COMMENT='决策节点（画布节点与规则配置）';

CREATE TABLE IF NOT EXISTS `decision_branch` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT,
  `decision_id` BIGINT      NOT NULL COMMENT '所属决策版本',
  `from_node`   VARCHAR(32) NOT NULL COMMENT '起点节点键',
  `from_port`   VARCHAR(32) NULL COMMENT '起点端口：分支节点为 c0/c1/else，其它节点为空',
  `to_node`     VARCHAR(32) NOT NULL COMMENT '终点节点键',
  `label`       VARCHAR(64) NULL COMMENT '连线标签',
  PRIMARY KEY (`id`),
  KEY `idx_branch_decision` (`decision_id`)
) ENGINE=InnoDB COMMENT='决策连线（画布信息）';

-- 策略编排演示草稿：loan_apply 的 v13，覆盖 分支 / 决策(case-when) / 交叉决策 / 普通决策 四种节点
INSERT INTO `decision` (`strategy_id`,`version_no`,`status`,`description`)
SELECT 1,'v13','DRAFT','编排演示草稿：分支 / 决策(case-when) / 交叉决策 / 普通决策'
WHERE NOT EXISTS (SELECT 1 FROM `decision` WHERE `strategy_id`=1 AND `version_no`='v13');

INSERT INTO `rule` (`decision_id`,`node_key`,`node_type`,`title`,`x`,`y`,`config_json`)
SELECT d.id, t.node_key, t.node_type, t.title, t.x, t.y, t.config_json
FROM (SELECT id FROM `decision` WHERE `strategy_id`=1 AND `version_no`='v13') d
JOIN (
  SELECT 'n_001' node_key,'START' node_type,'开始·入参声明' title,380 x,40 y,'{}' config_json
  UNION ALL SELECT 'n_002','BRANCH','分支·风险初筛',380,150,
    '{"conditions":[{"expr":"feat.user_overdue_level == ''H''","label":"高逾期等级"},{"expr":"param.apply_amount >= 100000","label":"大额申请"}],"elseLabel":"其余客群"}'
  UNION ALL SELECT 'n_003','CASE','决策·逾期规则表',110,310,
    '{"rows":[{"cond":"overdue_cnt >= 3","conclusion":"REJECT","reason":"R001 近90天逾期次数≥3","note":"高逾期直接拒绝"},{"cond":"overdue_cnt >= 1","conclusion":"REVIEW","reason":"R002 存在历史逾期记录","note":"转人工复核"},{"cond":"multi_loan_cnt >= 5","conclusion":"REJECT","reason":"R003 多头借贷≥5家","note":""}]}'
  UNION ALL SELECT 'n_004','CROSS','交叉决策·客群×额度',560,310,
    '{"rowVar":"feat.risk_level","colVar":"param.apply_amount","rowValues":["HIGH","MID","LOW"],"colValues":["<1万","1万-5万","≥5万"],"cells":[["REJECT","REJECT","REJECT"],["REVIEW","REVIEW","REJECT"],["PASS","REVIEW","REVIEW"]]}'
  UNION ALL SELECT 'n_005','NORMAL','普通决策·综合评分',380,455,
    '{"rules":[{"cond":"risk_score >= 750","conclusion":"PASS","reason":"评分优秀，直接通过"},{"cond":"risk_score >= 620","conclusion":"REVIEW","reason":"评分中等，转人工"},{"cond":"risk_score < 620","conclusion":"REJECT","reason":"评分不足准入线"}]}'
  UNION ALL SELECT 'n_006','END','结束·组装输出',380,560,'{}'
) t
WHERE NOT EXISTS (SELECT 1 FROM `rule` WHERE `decision_id` = d.id);

INSERT INTO `decision_branch` (`decision_id`,`from_node`,`from_port`,`to_node`,`label`)
SELECT d.id, t.from_node, t.from_port, t.to_node, t.label
FROM (SELECT id FROM `decision` WHERE `strategy_id`=1 AND `version_no`='v13') d
JOIN (
  SELECT 'n_001' from_node,NULL from_port,'n_002' to_node,NULL label
  UNION ALL SELECT 'n_002','c0','n_003','高逾期等级'
  UNION ALL SELECT 'n_002','c1','n_004','大额申请'
  UNION ALL SELECT 'n_002','else','n_005','其余客群'
  UNION ALL SELECT 'n_003',NULL,'n_006',NULL
  UNION ALL SELECT 'n_004',NULL,'n_006',NULL
  UNION ALL SELECT 'n_005',NULL,'n_006',NULL
) t
WHERE NOT EXISTS (SELECT 1 FROM `decision_branch` WHERE `decision_id` = d.id);
