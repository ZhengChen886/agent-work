package com.cloud.alibaba.ai.example.skills.skillsagentexample.trace;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentMetrics {
    private static final Logger log = LoggerFactory.getLogger(AgentMetrics.class);
    
    private final MeterRegistry meterRegistry;
    private final Counter toolCallCounter;
    private final Counter toolCallSuccessCounter;
    private final Counter toolCallFailureCounter;
    private final Timer agentExecutionTimer;
    private final Timer toolExecutionTimer;
    private final Counter semanticInterpretationCounter;
    private final Counter promptBlockedCounter;
    private final Map<String, Counter> toolCallCountersByTool;

    // Token 用量与成本估算指标
    private final Counter inputTokensCounter;
    private final Counter outputTokensCounter;
    private final Counter requestWithTokensCounter;
    private final Counter estimatedCostCounter;

    /** 每百万 input token 的成本（美元），DeepSeek-V3 参考价 */
    private static final double COST_PER_MILLION_INPUT_TOKENS = 0.14;
    /** 每百万 output token 的成本（美元），DeepSeek-V3 参考价 */
    private static final double COST_PER_MILLION_OUTPUT_TOKENS = 0.28;

    public AgentMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.toolCallCountersByTool = new ConcurrentHashMap<>();
        
        this.toolCallCounter = Counter.builder("agent.tool.calls.total")
            .description("Total number of tool calls")
            .tag("application", "skills-agent")
            .register(meterRegistry);
        
        this.toolCallSuccessCounter = Counter.builder("agent.tool.calls.success")
            .description("Number of successful tool calls")
            .tag("application", "skills-agent")
            .register(meterRegistry);
        
        this.toolCallFailureCounter = Counter.builder("agent.tool.calls.failure")
            .description("Number of failed tool calls")
            .tag("application", "skills-agent")
            .register(meterRegistry);
        
        this.agentExecutionTimer = Timer.builder("agent.execution.time")
            .description("Agent execution time")
            .tag("application", "skills-agent")
            .register(meterRegistry);
        
        this.toolExecutionTimer = Timer.builder("agent.tool.execution.time")
            .description("Tool execution time")
            .tag("application", "skills-agent")
            .register(meterRegistry);
        
        this.semanticInterpretationCounter = Counter.builder("agent.semantic.interpretations")
            .description("Number of semantic interpretations")
            .tag("application", "skills-agent")
            .register(meterRegistry);
        
        this.promptBlockedCounter = Counter.builder("agent.prompt.blocked")
            .description("Number of blocked prompts")
            .tag("application", "skills-agent")
            .register(meterRegistry);

        // Token 用量指标
        this.inputTokensCounter = Counter.builder("agent.tokens.input.total")
            .description("Total input tokens consumed")
            .tag("application", "skills-agent")
            .register(meterRegistry);

        this.outputTokensCounter = Counter.builder("agent.tokens.output.total")
            .description("Total output tokens generated")
            .tag("application", "skills-agent")
            .register(meterRegistry);

        this.requestWithTokensCounter = Counter.builder("agent.tokens.requests.total")
            .description("Number of requests with token usage tracking")
            .tag("application", "skills-agent")
            .register(meterRegistry);

        this.estimatedCostCounter = Counter.builder("agent.cost.estimated.total")
            .description("Estimated total cost in USD cents")
            .tag("application", "skills-agent")
            .register(meterRegistry);
        
        log.info("Agent metrics initialized");
    }

    public void recordToolCall(String toolName) {
        toolCallCounter.increment();
        getToolCounter(toolName).increment();
        log.debug("Recorded tool call: {}", toolName);
    }

    public void recordToolSuccess(String toolName) {
        toolCallSuccessCounter.increment();
        getToolCounter(toolName).increment();
    }

    public void recordToolFailure(String toolName) {
        toolCallFailureCounter.increment();
        getToolCounter(toolName).increment();
        log.warn("Tool call failed: {}", toolName);
    }

    public void recordToolExecution(String toolName, long durationMs) {
        toolExecutionTimer.record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        log.debug("Recorded tool execution time: {} - {}ms", toolName, durationMs);
    }

    public void recordAgentExecution(long durationMs) {
        agentExecutionTimer.record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        log.debug("Recorded agent execution time: {}ms", durationMs);
    }

    public void recordSemanticInterpretation() {
        semanticInterpretationCounter.increment();
        log.debug("Recorded semantic interpretation");
    }

    public void recordPromptBlocked() {
        promptBlockedCounter.increment();
        log.debug("Recorded prompt blocked");
    }

    /**
     * 记录一次请求的 token 用量并估算成本。
     *
     * @param inputTokens  输入 token 数
     * @param outputTokens 输出 token 数
     */
    public void recordTokenUsage(int inputTokens, int outputTokens) {
        if (inputTokens > 0) {
            inputTokensCounter.increment(inputTokens);
        }
        if (outputTokens > 0) {
            outputTokensCounter.increment(outputTokens);
        }
        requestWithTokensCounter.increment();

        // 估算成本（美元），乘以 100 转为美分存储，避免浮点数精度问题
        double costUsd = (inputTokens / 1_000_000.0 * COST_PER_MILLION_INPUT_TOKENS)
                       + (outputTokens / 1_000_000.0 * COST_PER_MILLION_OUTPUT_TOKENS);
        long costCents = Math.round(costUsd * 100);
        if (costCents > 0) {
            estimatedCostCounter.increment(costCents);
        }
        log.debug("Recorded token usage: input={}, output={}, estimatedCost={} cents",
                inputTokens, outputTokens, costCents);
    }

    private Counter getToolCounter(String toolName) {
        return toolCallCountersByTool.computeIfAbsent(toolName, name ->
            Counter.builder("agent.tool.calls.by.tool")
                .description("Tool calls by tool name")
                .tag("application", "skills-agent")
                .tag("tool", name)
                .register(meterRegistry)
        );
    }

    public Map<String, Object> getMetricsSummary() {
        Map<String, Object> summary = new ConcurrentHashMap<>();
        summary.put("total_tool_calls", toolCallCounter.count());
        summary.put("successful_tool_calls", toolCallSuccessCounter.count());
        summary.put("failed_tool_calls", toolCallFailureCounter.count());
        summary.put("agent_execution_time_avg_ms", agentExecutionTimer.mean(java.util.concurrent.TimeUnit.MILLISECONDS));
        summary.put("tool_execution_time_avg_ms", toolExecutionTimer.mean(java.util.concurrent.TimeUnit.MILLISECONDS));
        summary.put("semantic_interpretations", semanticInterpretationCounter.count());
        summary.put("prompts_blocked", promptBlockedCounter.count());
        // Token 与成本
        summary.put("total_input_tokens", inputTokensCounter.count());
        summary.put("total_output_tokens", outputTokensCounter.count());
        summary.put("requests_with_tokens", requestWithTokensCounter.count());
        summary.put("estimated_cost_cents", estimatedCostCounter.count());
        return summary;
    }
}
