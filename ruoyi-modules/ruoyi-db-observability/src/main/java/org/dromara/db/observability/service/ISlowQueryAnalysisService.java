package org.dromara.db.observability.service;

import org.dromara.db.observability.governance.DeterministicAnalyzer.AnalysisResult;

/**
 * 慢查询确定性分析服务（docs/07 §11）：频次/总耗时/锁等待/扫描返回比/首次/突增 + 表摘要 + 规则化建议。
 *
 * @author DataGate
 */
public interface ISlowQueryAnalysisService {
    AnalysisResult analyze(Long fingerprintId);
}
