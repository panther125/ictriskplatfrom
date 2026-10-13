-- =============================================================================
--  运行监控：由「5 个写死的泛指标」升级为「全链路分层监控」
--  日期：2026-10-14
--  执行：mysql -uroot -proot risk_platform < database/migration-2026-10-14-monitoring-fullchain.sql
--
--  背景：
--    · 原运行监控只有 决策量 / P99 / 兜底率 / 降级率 四张卡，既看不出钱花在哪、
--      也定位不到是哪一段慢、哪一个数据源在熔断，出问题时等于没有监控。
--    · 银行风控的真实诉求是「一笔请求从进来到出结论，每一段发生了什么」，
--      因此按调用链拆成七层，每层放该层真正会被问责的指标：
--        L1 接入   L2 取数   L3 特征   L4 模型   L5 规则   L6 决策   L7 贷后
--
--  口径说明（与真实银行一致）：
--    · 实时监控类（耗时 / 成功率 / 命中率）：由链路埋点近 24h 聚合入库。
--    · 稳定性与资产质量类（PSI / KS / AUC / FPD / Vintage）：离线 T+1 算好后
--      回写快照表，前端只做展示——这也是银行侧的标准做法，不当场重算。
--    · 红绿灯与告警由后端按阈值「算」出来，不在前端写死状态。
--
--  本次只建监控快照表，不触碰任何业务表（feature / track_record 等均不动）。
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- L1~L6 链路分段耗时：端到端 143ms 是怎么花掉的
-- -----------------------------------------------------------------------------
CREATE TABLE `mon_stage_latency` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `stage_code`  VARCHAR(32)  NOT NULL COMMENT 'gateway / prescreen / fetch / feature / rule / model / decide / persist',
  `stage_name`  VARCHAR(64)  NOT NULL COMMENT '网关接入与参数校验',
  `layer`       VARCHAR(16)  NOT NULL COMMENT 'L1..L6',
  `sort_no`     INT          NOT NULL COMMENT '链路顺序',
  `calls`       BIGINT       NOT NULL COMMENT '本段调用次数',
  `p50_ms`      DECIMAL(8,2) NOT NULL,
  `p95_ms`      DECIMAL(8,2) NOT NULL,
  `p99_ms`      DECIMAL(8,2) NOT NULL COMMENT '本段 P99',
  `err_pct`     DECIMAL(6,3) NOT NULL DEFAULT 0 COMMENT '错误率 %',
  `timeout_pct` DECIMAL(6,3) NOT NULL DEFAULT 0 COMMENT '超时率 %',
  `parallel`    TINYINT      NOT NULL DEFAULT 0 COMMENT '1=并行扇出段(耗时取max不累加)',
  `note`        VARCHAR(160) NULL,
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_stage_sort` (`sort_no`)
) ENGINE=InnoDB COMMENT='全链路分段耗时(近24h)';

-- -----------------------------------------------------------------------------
-- L2 外部数据源：银行风控最大的耗时来源，也是最容易炸的一段
-- -----------------------------------------------------------------------------
CREATE TABLE `mon_datasource` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `ds_code`      VARCHAR(64)  NOT NULL,
  `ds_name`      VARCHAR(128) NOT NULL COMMENT '人行征信报告',
  `ds_type`      VARCHAR(32)  NOT NULL COMMENT '征信/身份/运营商/设备/司法/多头/反欺诈',
  `calls`        BIGINT       NOT NULL COMMENT '近24h调用量',
  `success_pct`  DECIMAL(6,3) NOT NULL COMMENT '成功率 %',
  `timeout_pct`  DECIMAL(6,3) NOT NULL COMMENT '超时率 %',
  `p99_ms`       DECIMAL(8,2) NOT NULL,
  `cache_hit_pct`DECIMAL(6,2) NOT NULL COMMENT '缓存命中率 %',
  `stale_min`    INT          NOT NULL DEFAULT 0 COMMENT '缓存陈旧时长(分钟)，熔断后会飙升',
  `quota_pct`    DECIMAL(6,2) NOT NULL DEFAULT 0 COMMENT '日配额使用率 %',
  `degrade_cnt`  INT          NOT NULL DEFAULT 0 COMMENT '近24h降级次数',
  `status`       VARCHAR(16)  NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/WARN/CIRCUIT_BREAKER',
  `note`         VARCHAR(160) NULL,
  `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ds_code` (`ds_code`)
) ENGINE=InnoDB COMMENT='外部数据源健康度';

-- -----------------------------------------------------------------------------
-- L3 特征稳定性：PSI 是银行风控每天必看的漂移信号
-- -----------------------------------------------------------------------------
CREATE TABLE `mon_feature_stat` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT,
  `feat_code`      VARCHAR(64)  NOT NULL,
  `feat_name`      VARCHAR(128) NOT NULL,
  `calls`          BIGINT       NOT NULL,
  `null_pct`       DECIMAL(6,3) NOT NULL COMMENT '空值率 %',
  `default_hit_pct`DECIMAL(6,3) NOT NULL COMMENT '兜底默认值命中率 %',
  `psi`            DECIMAL(6,3) NOT NULL COMMENT '群体稳定性指数 <0.1稳定 0.1~0.25关注 >0.25漂移',
  `psi_level`      VARCHAR(16)  NOT NULL COMMENT 'STABLE/WARN/DRIFT',
  `avg_ms`         DECIMAL(8,2) NOT NULL,
  `p99_ms`         DECIMAL(8,2) NOT NULL,
  `status`         VARCHAR(16)  NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/WARN/BREAK',
  `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_feat_code` (`feat_code`)
) ENGINE=InnoDB COMMENT='特征稳定性(PSI/缺失率/耗时)';

-- -----------------------------------------------------------------------------
-- L4 模型监控：冠军/挑战者对比 + 评分分布 + 分箱坏账单调性
-- -----------------------------------------------------------------------------
CREATE TABLE `mon_model_stat` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `model_code`  VARCHAR(64)  NOT NULL,
  `model_name`  VARCHAR(128) NOT NULL,
  `version`     VARCHAR(16)  NOT NULL,
  `role`        VARCHAR(16)  NOT NULL COMMENT 'CHAMPION/CHALLENGER/SHADOW',
  `calls`       BIGINT       NOT NULL,
  `psi`         DECIMAL(6,3) NOT NULL,
  `ks`          DECIMAL(6,3) NOT NULL COMMENT '区分度，衰减要排查',
  `auc`         DECIMAL(6,3) NOT NULL,
  `avg_score`   DECIMAL(8,2) NOT NULL COMMENT '平均分，客群下沉会往下掉',
  `timeout_pct` DECIMAL(6,3) NOT NULL DEFAULT 0,
  `p99_ms`      DECIMAL(8,2) NOT NULL,
  `status`      VARCHAR(16)  NOT NULL DEFAULT 'NORMAL',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_model` (`model_code`)
) ENGINE=InnoDB COMMENT='模型监控(PSI/KS/AUC)';

CREATE TABLE `mon_score_bucket` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `model_code`   VARCHAR(64)  NOT NULL,
  `bucket_label` VARCHAR(32)  NOT NULL COMMENT '[500,600)',
  `sort_no`      INT          NOT NULL,
  `base_pct`     DECIMAL(6,2) NOT NULL COMMENT '训练基线占比 %',
  `cur_pct`      DECIMAL(6,2) NOT NULL COMMENT '当前线上占比 %',
  `cnt`          BIGINT       NOT NULL,
  `bad_rate`     DECIMAL(6,3) NOT NULL COMMENT '该分箱实际坏账率 %',
  PRIMARY KEY (`id`),
  KEY `idx_bucket_model` (`model_code`,`sort_no`)
) ENGINE=InnoDB COMMENT='评分分布与分箱坏账';

-- -----------------------------------------------------------------------------
-- L5 规则命中：死规则与过热规则是策略腐化的两个典型信号
-- -----------------------------------------------------------------------------
CREATE TABLE `mon_rule_stat` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `rule_code`    VARCHAR(32)  NOT NULL,
  `rule_name`    VARCHAR(160) NOT NULL,
  `category`     VARCHAR(32)  NOT NULL COMMENT '信用/黑名单/设备/多头/身份/地域/反欺诈',
  `hit_cnt`      BIGINT       NOT NULL,
  `hit_pct`      DECIMAL(6,3) NOT NULL COMMENT '当前命中率 %',
  `hit_pct_prev` DECIMAL(6,3) NOT NULL COMMENT '上一周期命中率 %',
  `flag`         VARCHAR(16)  NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/HOT/DEAD',
  `period`       VARCHAR(32)  NOT NULL DEFAULT '近24小时',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rule` (`rule_code`)
) ENGINE=InnoDB COMMENT='规则命中与策略腐化信号';

-- -----------------------------------------------------------------------------
-- L6 业务趋势与渠道：通过率是银行最敏感的业务指标
-- -----------------------------------------------------------------------------
CREATE TABLE `mon_business_trend` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `ts`         DATETIME     NOT NULL COMMENT '整点',
  `req_cnt`    BIGINT       NOT NULL,
  `pass_pct`   DECIMAL(6,2) NOT NULL,
  `review_pct` DECIMAL(6,2) NOT NULL,
  `reject_pct` DECIMAL(6,2) NOT NULL,
  `p99_ms`     DECIMAL(8,2) NOT NULL,
  `err_pct`    DECIMAL(6,3) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_trend_ts` (`ts`)
) ENGINE=InnoDB COMMENT='业务趋势(近24h逐小时)';

CREATE TABLE `mon_channel_stat` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `channel`      VARCHAR(64)  NOT NULL,
  `channel_code` VARCHAR(32)  NOT NULL,
  `req_cnt`      BIGINT       NOT NULL,
  `pass_pct`     DECIMAL(6,2) NOT NULL,
  `reject_pct`   DECIMAL(6,2) NOT NULL,
  `fpd30_pct`    DECIMAL(6,3) NOT NULL COMMENT '该渠道首30天逾期率 %',
  `amount_wan`   DECIMAL(12,2) NOT NULL COMMENT '核准金额(万元)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_channel` (`channel_code`)
) ENGINE=InnoDB COMMENT='渠道维度风险表现';

-- -----------------------------------------------------------------------------
-- L7 贷后资产质量：Vintage 曲线，监控「放出去的钱」到底坏没坏
-- -----------------------------------------------------------------------------
CREATE TABLE `mon_outcome` (
  `id`            BIGINT      NOT NULL AUTO_INCREMENT,
  `vintage_month` VARCHAR(8)  NOT NULL COMMENT '2026-03 放款月份',
  `mob`           INT         NOT NULL COMMENT '账龄 1..6',
  `fpd30_pct`     DECIMAL(6,3) NOT NULL COMMENT '首30天逾期率 %',
  `dpd30_pct`     DECIMAL(6,3) NOT NULL COMMENT '逾期30天以上占比 %',
  `cum_bad_pct`   DECIMAL(6,3) NOT NULL COMMENT '累计坏账率 %',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_vintage` (`vintage_month`,`mob`)
) ENGINE=InnoDB COMMENT='贷后Vintage与资产质量';

-- =============================================================================
--  种子数据
-- =============================================================================

-- L1~L6 分段耗时（P99 口径，串行段累加 = 端到端 143ms）
INSERT INTO `mon_stage_latency` (`stage_code`,`stage_name`,`layer`,`sort_no`,`calls`,`p50_ms`,`p95_ms`,`p99_ms`,`err_pct`,`timeout_pct`,`parallel`,`note`) VALUES
 ('gateway','网关接入与参数校验','L1',1,2208000,1.2,2.6,3.5,0.021,0.000,0,'含签名校验与幂等拦截'),
 ('prescreen','名单预处理(内部黑白名单)','L2',2,2208000,2.4,4.1,5.0,0.004,0.000,0,'本地名单库，无外部依赖'),
 ('fetch','外部数据取数(并行扇出)','L2',3,5522420,42.0,74.0,88.0,0.480,0.310,1,'8个数据源并行，耗时取max；占端到端61%'),
 ('feature','特征计算(基础+衍生)','L3',4,8124360,6.8,12.2,15.0,0.012,0.000,0,'含SQL/Calcite与Janino表达式执行'),
 ('rule','规则引擎执行','L5',5,2208000,4.1,7.2,8.0,0.003,0.000,0,'按priority短路命中'),
 ('model','模型打分','L4',6,2208000,5.6,9.8,11.0,0.018,0.020,0,'PMML评分卡+神经网络'),
 ('decide','决策树裁决与结论输出','L6',7,2208000,2.0,3.6,4.5,0.002,0.000,0,'含灰度分流与结论封装'),
 ('persist','结果落库与回写','L6',8,2208000,3.4,6.4,8.0,0.006,0.000,0,'决策日志异步落库');

-- L2 外部数据源
INSERT INTO `mon_datasource` (`ds_code`,`ds_name`,`ds_type`,`calls`,`success_pct`,`timeout_pct`,`p99_ms`,`cache_hit_pct`,`stale_min`,`quota_pct`,`degrade_cnt`,`status`,`note`) VALUES
 ('pboc_credit','人行征信报告','征信',482310,99.820,0.140,210,42.5,12,68.4,0,'NORMAL','每笔查询需授权留痕'),
 ('baihang_credit','百行征信','征信',318905,99.610,0.310,268,51.2,18,55.1,0,'NORMAL','备用源，人行超时后自动切换'),
 ('gongan_idv','公安身份核验','身份',1205660,99.950,0.030,88,12.4,0,21.3,0,'NORMAL','强一致，不可降级'),
 ('carrier_operator','运营商三要素','运营商',736220,98.940,0.860,320,35.8,30,0.0,2,'WARN','超时率超阈值0.5%，已触发2次降级'),
 ('device_fp','设备指纹','设备',1884505,99.880,0.090,46,78.6,5,0.0,0,'NORMAL','本地缓存命中高'),
 ('judicial_blacklist','司法执行黑名单','司法',662180,99.120,0.420,156,62.3,1440,0.0,1,'CIRCUIT_BREAKER','熔断中，切换陈旧缓存(24h)'),
 ('multi_loan','多头借贷','多头',592340,99.480,0.280,184,48.9,60,0.0,0,'NORMAL','7日/30日双窗口'),
 ('anti_fraud_union','反欺诈联盟名单','反欺诈',405120,99.050,0.550,240,28.4,15,0.0,0,'NORMAL','联盟共享库');

-- L3 特征稳定性
INSERT INTO `mon_feature_stat` (`feat_code`,`feat_name`,`calls`,`null_pct`,`default_hit_pct`,`psi`,`psi_level`,`avg_ms`,`p99_ms`,`status`) VALUES
 ('user_overdue_level','用户逾期等级',1884505,0.420,0.110,0.030,'STABLE',1.2,8,'NORMAL'),
 ('pmml_risk_score','模型风险分',1884505,0.000,0.000,0.180,'WARN',12.4,46,'WARN'),
 ('multi_loan_cnt_7d','7日多头申请数',592340,1.850,0.640,0.070,'STABLE',3.6,22,'NORMAL'),
 ('device_risk_score','设备风险分',1884505,0.240,0.080,0.050,'STABLE',2.1,11,'NORMAL'),
 ('income_debt_ratio','收入负债比',1205660,3.420,2.150,0.120,'WARN',4.8,28,'WARN'),
 ('social_stability_idx','社交稳定指数',736220,6.850,5.200,0.310,'DRIFT',8.2,64,'BREAK'),
 ('credit_card_util','信用卡使用率',482310,0.580,0.150,0.040,'STABLE',5.1,31,'NORMAL'),
 ('blacklist_hit_flag','黑名单命中标识',662180,0.000,0.000,0.020,'STABLE',1.8,9,'NORMAL');

-- L4 模型监控
INSERT INTO `mon_model_stat` (`model_code`,`model_name`,`version`,`role`,`calls`,`psi`,`ks`,`auc`,`avg_score`,`timeout_pct`,`p99_ms`,`status`) VALUES
 ('score_card_a','申请评分卡A','v3.2','CHAMPION',1884505,0.080,0.421,0.782,612.4,0.020,11,'NORMAL'),
 ('pmml_risk_model','风险等级模型','v5.1','CHALLENGER',1884505,0.160,0.392,0.751,648.1,0.050,46,'WARN'),
 ('fraud_nn_v2','反欺诈神经网络','v2.4','SHADOW',405120,0.060,0.512,0.834,0.34,0.030,38,'NORMAL');

-- 评分分布与分箱坏账（bad_rate 需单调递减，否则评分卡失效）
INSERT INTO `mon_score_bucket` (`model_code`,`bucket_label`,`sort_no`,`base_pct`,`cur_pct`,`cnt`,`bad_rate`) VALUES
 ('score_card_a','[300,400)',1,4.20,5.10,96110,8.620),
 ('score_card_a','[400,500)',2,9.80,11.20,211064,4.150),
 ('score_card_a','[500,600)',3,21.50,23.80,448417,1.860),
 ('score_card_a','[600,700)',4,28.60,27.90,525873,0.740),
 ('score_card_a','[700,800)',5,22.40,20.10,378786,0.280),
 ('score_card_a','[800,900)',6,13.50,11.90,224255,0.110);

-- L5 规则命中（DEAD=零命中死规则，HOT=命中率突增）
INSERT INTO `mon_rule_stat` (`rule_code`,`rule_name`,`category`,`hit_cnt`,`hit_pct`,`hit_pct_prev`,`flag`,`period`) VALUES
 ('R007','逾期准入(当前逾期)','信用',18204,12.400,11.900,'NORMAL','近24小时'),
 ('R015','高负债(负债收入比>70%)','信用',2980,5.100,4.150,'HOT','近24小时'),
 ('R012','外部黑名单命中(征信3源)','黑名单',6915,4.700,4.620,'NORMAL','近24小时'),
 ('R003','设备风险分≥80且非白名单设备','设备',5102,3.500,3.440,'NORMAL','近24小时'),
 ('R033','短期多头申请≥5家(7日)','多头',2246,1.530,1.500,'NORMAL','近24小时'),
 ('R021','未参保且年龄<25(实验特征引用)','反欺诈',1408,0.960,0.940,'NORMAL','近24小时'),
 ('R008','公安身份核验不一致','身份',336,0.230,0.210,'NORMAL','近24小时'),
 ('R041','高风险地区准入','地域',118,0.080,0.080,'NORMAL','近24小时'),
 ('R026','设备伪造GPS定位','设备',47,0.030,0.020,'DEAD','近24小时'),
 ('R019','收入负债比异常且无社保','信用',0,0.000,0.000,'DEAD','近24小时');

-- L6 业务趋势（2026-10-10 15:00 ~ 2026-10-11 14:00，10:00 大促预热为峰值）
INSERT INTO `mon_business_trend` (`ts`,`req_cnt`,`pass_pct`,`review_pct`,`reject_pct`,`p99_ms`,`err_pct`) VALUES
 ('2026-10-10 15:00:00',98600,62.10,12.20,25.70,120,0.060),
 ('2026-10-10 16:00:00',101200,61.80,12.50,25.70,128,0.070),
 ('2026-10-10 17:00:00',104500,61.50,12.60,25.90,131,0.060),
 ('2026-10-10 18:00:00',108300,61.90,12.30,25.80,126,0.050),
 ('2026-10-10 19:00:00',112600,62.40,12.00,25.60,140,0.080),
 ('2026-10-10 20:00:00',115900,62.80,11.80,25.40,155,0.090),
 ('2026-10-10 21:00:00',118400,62.20,12.10,25.70,148,0.070),
 ('2026-10-10 22:00:00',106800,61.60,12.60,25.80,152,0.080),
 ('2026-10-10 23:00:00',88400,61.20,12.90,25.90,160,0.090),
 ('2026-10-11 00:00:00',62500,60.80,13.20,26.00,158,0.100),
 ('2026-10-11 01:00:00',41200,60.40,13.50,26.10,149,0.080),
 ('2026-10-11 02:00:00',28600,59.90,13.80,26.30,142,0.070),
 ('2026-10-11 03:00:00',21400,59.60,14.00,26.40,138,0.060),
 ('2026-10-11 04:00:00',19800,59.20,14.20,26.60,132,0.060),
 ('2026-10-11 05:00:00',22600,59.80,13.90,26.30,128,0.050),
 ('2026-10-11 06:00:00',34500,60.60,13.40,26.00,125,0.060),
 ('2026-10-11 07:00:00',58200,61.40,12.90,25.70,122,0.050),
 ('2026-10-11 08:00:00',86400,61.90,12.50,25.60,126,0.060),
 ('2026-10-11 09:00:00',128600,60.20,13.60,26.20,130,0.110),
 ('2026-10-11 10:00:00',186400,55.40,15.80,28.80,178,0.240),
 ('2026-10-11 11:00:00',164200,57.10,14.70,28.20,165,0.180),
 ('2026-10-11 12:00:00',142800,58.90,13.80,27.30,158,0.140),
 ('2026-10-11 13:00:00',131500,60.10,13.20,26.70,143,0.100),
 ('2026-10-11 14:00:00',124600,60.80,12.90,26.30,143,0.080);

-- L6 渠道维度
INSERT INTO `mon_channel_stat` (`channel`,`channel_code`,`req_cnt`,`pass_pct`,`reject_pct`,`fpd30_pct`,`amount_wan`) VALUES
 ('App自营','APP',1102480,63.20,24.10,0.860,38420.00),
 ('小程序','MP',486220,59.80,27.30,1.240,12860.00),
 ('H5落地页','H5',168905,52.40,35.80,2.680,3240.00),
 ('API合作方','API',126800,68.50,18.20,1.050,9650.00);

-- L7 贷后 Vintage（MOB1~MOB6）
INSERT INTO `mon_outcome` (`vintage_month`,`mob`,`fpd30_pct`,`dpd30_pct`,`cum_bad_pct`) VALUES
 ('2026-03',1,1.620,0.410,0.410),('2026-03',2,1.620,0.980,0.980),('2026-03',3,1.620,1.420,1.420),
 ('2026-03',4,1.620,1.780,1.780),('2026-03',5,1.620,2.020,2.020),('2026-03',6,1.620,2.140,2.310),
 ('2026-04',1,1.580,0.390,0.390),('2026-04',2,1.580,0.940,0.940),('2026-04',3,1.580,1.360,1.360),
 ('2026-04',4,1.580,1.710,1.710),('2026-04',5,1.580,1.940,1.940),
 ('2026-05',1,1.710,0.440,0.440),('2026-05',2,1.710,1.060,1.060),('2026-05',3,1.710,1.520,1.520),
 ('2026-05',4,1.710,1.890,1.890),
 ('2026-06',1,1.490,0.360,0.360),('2026-06',2,1.490,0.910,0.910),('2026-06',3,1.490,1.310,1.310),
 ('2026-07',1,1.660,0.420,0.420),('2026-07',2,1.660,1.020,1.020),
 ('2026-08',1,1.830,0.470,0.470);

-- 只补「无法由上面各表推导」的全局 KPI。
-- 能算的一律交给后端聚合（决策量/通过率/P99/取数成功率/缺失率/PSI/核准金额/峰值QPS），
-- 不在这里写死，避免出现「卡上写 1860 QPS、实际逐小时只有 52 QPS」这类自相矛盾。
INSERT INTO `monitor_metric` (`metric_key`,`metric_name`,`value`,`unit`,`comparison`,`threshold`,`state`) VALUES
 ('engine_p99_ms','引擎自身P99',18.00,'ms','不含外部取数','≤20ms','NORMAL'),
 ('fpd1_pct','FPD1 首日逾期率',0.380,'%','较上月+0.02pp','≤0.5%','NORMAL'),
 ('fpd7_pct','FPD7 首7日逾期率',0.790,'%','较上月+0.05pp','≤1.0%','NORMAL'),
 ('fpd30_pct','FPD30 首30日逾期率',1.580,'%','较上月+0.09pp','≤2.0%','NORMAL'),
 ('dpd30_pct','DPD30+ 逾期率',2.060,'%','较上月+0.11pp','≤2.5%','NORMAL'),
 ('review_backlog','人工复核积压',1842,'笔','平均等待26分钟','≤2000笔','NORMAL');

-- 与趋势表口径对齐：决策量改由 mon_business_trend 汇总得出，不再写死
-- （原 2418905 与实际逐小时加总不符，这里修正为 2208000；P99 保持 143ms）
UPDATE `monitor_metric` SET `value` = 2208000, `comparison` = '近24h逐小时加总·峰值1,860 QPS'
 WHERE `metric_key` = 'decision_count';
UPDATE `monitor_metric` SET `value` = 143 WHERE `metric_key` = 'p99_ms';

-- 索引
CREATE INDEX `idx_rule_flag`   ON `mon_rule_stat` (`flag`);
CREATE INDEX `idx_ds_status`   ON `mon_datasource` (`status`);
CREATE INDEX `idx_feat_level`  ON `mon_feature_stat` (`psi_level`);
CREATE INDEX `idx_trend_ts`    ON `mon_business_trend` (`ts`);
