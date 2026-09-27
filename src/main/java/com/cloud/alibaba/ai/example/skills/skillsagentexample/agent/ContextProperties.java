package com.cloud.alibaba.ai.example.skills.skillsagentexample.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 上下文管理配置属性。
 * <p>
 * 绑定 application.yml 中的 {@code agent.context.*} 配置项，
 * 用于驱动 {@link LLMContextCompressor} 的动态 token 预算计算。
 */
@Component
@ConfigurationProperties(prefix = "agent.context")
public class ContextProperties {

    /** 模型上下文窗口大小（token） */
    private int maxContextTokens = 32768;

    /** 为模型输出预留的 token 数 */
    private int reservedOutputTokens = 4096;

    /** 摘要消息的最大 token 数 */
    private int summaryMaxTokens = 1024;

    /** 保留最近 N 轮对话原文（每轮 = user + assistant） */
    private int keepRecentRounds = 6;

    /**
     * 计算可用于输入的 token 预算 = 上下文窗口 - 预留输出 - 摘要上限。
     * 当历史消息 token 数超过此预算时触发压缩。
     */
    public int getInputBudgetTokens() {
        return maxContextTokens - reservedOutputTokens - summaryMaxTokens;
    }

    public int getMaxContextTokens() {
        return maxContextTokens;
    }

    public void setMaxContextTokens(int maxContextTokens) {
        this.maxContextTokens = maxContextTokens;
    }

    public int getReservedOutputTokens() {
        return reservedOutputTokens;
    }

    public void setReservedOutputTokens(int reservedOutputTokens) {
        this.reservedOutputTokens = reservedOutputTokens;
    }

    public int getSummaryMaxTokens() {
        return summaryMaxTokens;
    }

    public void setSummaryMaxTokens(int summaryMaxTokens) {
        this.summaryMaxTokens = summaryMaxTokens;
    }

    public int getKeepRecentRounds() {
        return keepRecentRounds;
    }

    public void setKeepRecentRounds(int keepRecentRounds) {
        this.keepRecentRounds = keepRecentRounds;
    }
}
