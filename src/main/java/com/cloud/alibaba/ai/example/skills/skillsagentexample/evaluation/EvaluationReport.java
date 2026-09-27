package com.cloud.alibaba.ai.example.skills.skillsagentexample.evaluation;

import java.util.List;

/**
 * 评测报告，包含所有用例的执行结果和汇总统计。
 */
public record EvaluationReport(
        String goldenSetVersion,
        int totalCases,
        int passedCases,
        int failedCases,
        double passRate,
        long totalDurationMs,
        List<EvaluationCaseResult> results
) {
}
