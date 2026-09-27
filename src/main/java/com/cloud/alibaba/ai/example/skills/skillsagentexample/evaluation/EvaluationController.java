package com.cloud.alibaba.ai.example.skills.skillsagentexample.evaluation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评测 API 控制器。
 * <p>
 * 提供 golden set 评测端点，用于回归测试和效果对比。
 * 每次 Prompt 或 Context 改动后，应运行此端点验证是否有退化。
 */
@RestController
@RequestMapping("/api/evaluation")
public class EvaluationController {

    private static final Logger log = LoggerFactory.getLogger(EvaluationController.class);

    private final EvaluationService evaluationService;

    public EvaluationController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    /**
     * 运行完整 golden set 评测。
     * <p>
     * 注意：此接口会真实调用大模型，可能耗时较长（每个用例几秒到十几秒）。
     */
    @GetMapping("/run")
    public EvaluationReport runEvaluation() {
        log.info("Starting golden set evaluation...");
        return evaluationService.runAll();
    }
}
