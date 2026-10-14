import axios from 'axios';

const http = axios.create({ baseURL: '/api', timeout: 15000 });

http.interceptors.response.use(
  (res) => {
    const body = res.data;
    if (body && body.code !== 0) {
      return Promise.reject(new Error(body.message || '请求失败'));
    }
    return body ? body.data : body;
  },
  (error) => {
    let msg;
    if (error.response) {
      const body = error.response.data;
      msg = (body && (body.message || body.error)) || `请求失败（HTTP ${error.response.status}）`;
    } else if (error.request) {
      msg = '无法连接服务器，请确认后端服务已启动（默认端口 8080）后再试';
    } else {
      msg = error.message || '请求发送失败，请重试';
    }
    return Promise.reject(new Error(msg));
  }
);

export const api = {
  dashboard: () => http.get('/dashboard'),
  views: {
    list: (params) => http.get('/views', { params: { page: 1, size: 9999, ...params } }).then(r => r.records ?? r),
    detail: (id) => http.get(`/views/${id}`),
    create: (data) => http.post('/views', data),
    update: (id, data) => http.put(`/views/${id}`, data),
    updateFields: (id, fields) => http.put(`/views/${id}/fields`, fields),
    updateInputs: (id, inputs) => http.put(`/views/${id}/inputs`, inputs),
    offline: (id) => http.post(`/views/${id}/offline`),
    publish: (id) => http.post(`/views/${id}/publish`),
    validate: (data) => http.post('/views/validate', data),
    // ---- gap endpoints ----
    datasourceHealth: (range) => http.get('/views/datasource-health', { params: { range } }),
    lineage: (id) => http.get(`/views/${id}/lineage`),
    degradeConfig: (id) => http.get(`/views/${id}/degrade-config`),
    updateDegradeConfig: (id, cfg) => http.put(`/views/${id}/degrade-config`, cfg),
    parseScript: (sourceType, scriptContent) => http.post('/views/parse-script', { sourceType, scriptContent }),
    upload: (sourceType, file) => {
      const fd = new FormData();
      fd.append('file', file);
      fd.append('sourceType', sourceType);
      return http.post('/views/upload', fd, { timeout: 60000 });
    }
  },
  features: {
    list: (params) => http.get('/features', { params: { page: 1, size: 9999, ...params } }).then(r => r.records ?? r),
    detail: (id) => http.get(`/features/${id}`),
    // ---- gap endpoints ----
    create: (data) => http.post('/features', data),
    update: (id, data) => http.put(`/features/${id}`, data),
    stats: () => http.get('/features/stats'),
    batchVerify: (ids) => http.post('/features/batch-verify', { ids }),
    offline: (id) => http.post(`/features/${id}/offline`),
    publish: (id) => http.post(`/features/${id}/publish`),
    validateName: (code, domain) => http.post('/features/validate-name', { code, domain }),
    similar: (code) => http.get('/features/similar', { params: { code } }),
    impact: (id) => http.get(`/features/${id}/impact`),
    parseInputs: (sql) => http.post('/features/parse-inputs', { sql }),
    trial: (id, inputs) => http.post(`/features/${id}/trial`, inputs || {}),
    precheck: (id) => http.get(`/features/${id}/precheck`),
    submitApproval: (id, remark) => http.post(`/features/${id}/submit-approval`, { remark }),
    // ---- 基础特征 / 衍生特征绑定 ----
    baseFeatures: (limit = 50) => http.get('/features/base-features', { params: { limit } }),
    resolveRefs: (expr) => http.post('/features/resolve-refs', { expr }),
    resolveInputs: (payload) => http.post('/features/resolve-inputs', payload),
    resolvedInputs: (id) => http.get(`/features/${id}/resolved-inputs`),
    syncInputs: (id) => http.post(`/features/${id}/sync-inputs`),
    // ---- 编辑态：入参 / 出参定义全量保存 ----
    saveInputs: (id, rows) => http.post(`/features/${id}/inputs`, rows),
    saveOutputs: (id, rows) => http.post(`/features/${id}/outputs`, rows),
    /** 改编码：后端会一并同步下游 @@ / feat. 引用，返回同步明细 */
    rename: (id, code) => http.post(`/features/${id}/rename`, { code })
  },
  trial: {
    // 试算可选的策略 + 版本清单（策略按 code 定位，避免前端写死 strategy_id）
    strategies: () => http.get('/trial/strategies'),
    // 试算入参需带 strategy_code（推荐）或 strategy_id，可选 version_id / version_no
    execute: (params) => http.post('/trial/execute', params),
    // 解析入参：按策略/版本扫描决策图引用，返回试算输入模板（策略入参 + 被引用特征的入参）
    resolveInputs: (params) => http.post('/trial/resolve-inputs', params),
    detail: (id) => http.get(`/trial/${id}`),
    recent: () => http.get('/trial/recent'),
    // ---- 用例集（按策略过滤，不传则全部） ----
    cases: (strategyId) => http.get('/trial/cases', { params: strategyId ? { strategyId } : {} }),
    createCase: (data) => http.post('/trial/cases', data),
    updateCase: (id, data) => http.put(`/trial/cases/${id}`, data),
    deleteCase: (id) => http.delete(`/trial/cases/${id}`),
    runAllCases: (strategyId) => http.post('/trial/cases/run-all', strategyId ? { strategyId } : {}),
    // ---- 批量回放 / 影子对比 ----
    batchReplay: (traceIds, strategyId, version) =>
      http.post('/trial/batch-replay', { traceIds, strategyId, version }),
    shadowCompare: (inputs, strategyId) =>
      http.post('/trial/shadow-compare', { inputs, strategyId })
  },
  crossTrees: {
    list: (strategyId) => http.get('/cross-trees', { params: { strategyId } }),
    detail: (id, slice) => http.get(`/cross-trees/${id}`, { params: { slice } }),
    // ---- gap endpoints ----
    updateCells: (id, cells) => http.put(`/cross-trees/${id}/cells`, { cells }),
    updateDimensions: (id, dimensions) => http.put(`/cross-trees/${id}/dimensions`, { dimensions }),
    compare: (id, onlineVersion) => http.get(`/cross-trees/${id}/compare`, { params: { onlineVersion } }),
    validate: (id) => http.get(`/cross-trees/${id}/validate`),
    submitApproval: (id) => http.post(`/cross-trees/${id}/submit-approval`)
  },
  strategies: {
    // 策略管理列表：草稿 / 已发布
    list: () => http.get('/strategies'),
    // 新增策略：落策略行并初始化 v1 草稿版本（可用 inheritFromStrategyId 指定初始画布的继承源）
    create: (body) => http.post('/strategies', body),
    // 策略内部版本列表：草稿 / 已发布 / 已下线 + 流量比例 + 切流方式
    versions: (strategyId) => http.get(`/strategies/${strategyId}/versions`),
    // 新增版本：新版本继承上一个画布的节点与连线配置（可用 baseVersionNo 指定继承源）
    createVersion: (strategyId, body) => http.post(`/strategies/${strategyId}/versions`, body || {}),
    // 查看某版本的画布（只读）：节点 + 连线 + 画布来源说明（canvasSource / canvasNote）
    graph: (versionId) => http.get(`/strategies/versions/${versionId}/graph`),
    // 决策级切流：为策略内多个版本分配份额（mode = RANDOM / PERCENT / WHITELIST）
    updateStrategyTraffic: (strategyId, body) => http.put(`/strategies/${strategyId}/traffic`, body),
    publishVersion: (versionId) => http.post(`/strategies/versions/${versionId}/publish`),
    offlineVersion: (versionId) => http.post(`/strategies/versions/${versionId}/offline`)
  },
  decisions: {
    // 打开编排草稿：不带 versionId 取最新草稿；带 versionId 打开该版本，无画布时自动派生新草稿
    byStrategy: (strategyId, versionId) => http.get(`/decisions/by-strategy/${strategyId}${versionId ? `?versionId=${versionId}` : ''}`),
    // 全量保存画布：节点入 rule 表，连线入 decision_branch 表
    saveGraph: (id, data) => http.put(`/decisions/${id}/graph`, data)
  },
  monitoring: () => http.get('/monitoring'),
  track: {
    invoke: (params) => http.post('/track/invoke', params),
    trial: (params) => http.post('/track/trial', params),
    replay: (id) => http.post(`/track/${id}/replay`),
    recent: () => http.get('/track/recent'),
    search: (q) => http.get('/track/search', { params: q }),
    detail: (id) => http.get(`/track/${id}`),
    // 该 trace 对应的策略画布（只读，节点/连线），用于运行路线高亮
    canvas: (id) => http.get(`/track/${id}/canvas`),
    // ---- gap endpoints ----
    stats: (range) => http.get('/track/stats', { params: { range } }),
    samplingConfig: () => http.get('/track/sampling-config'),
    updateSamplingConfig: (cfg) => http.put('/track/sampling-config', cfg),
    degradeSources: (range) => http.get('/track/degrade-sources', { params: { range } })
  }
};

export default http;
