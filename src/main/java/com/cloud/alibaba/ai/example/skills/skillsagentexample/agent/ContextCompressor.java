package com.cloud.alibaba.ai.example.skills.skillsagentexample.agent;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.entity.Message;

import java.util.List;

/**
 * 上下文压缩组件：检查历史消息是否超过阈值，若超过则用 LLM 生成早期对话的摘要，
 * 并保留最近 N 轮原文，同时标记需要被删除/替换的早期消息。
 */
public interface ContextCompressor {

    /**
     * 检查并执行压缩。
     *
     * @param history 当前完整历史（不含本次用户新问题）
     * @return 压缩结果：包含传给 Agent 的消息列表（可能带 role=summary 的摘要消息），
     *         以及 compressed 标志。compressed=false 表示无需压缩、全部原文返回；
     *         compressed=true 表示 history 已经被处理过，需要外部持久化层配合
     *         删除早期消息并写入 summary 消息。
     */
    CompressionResult compress(List<Message> history);

    record CompressionResult(
            List<Message> messagesForAgent,
            boolean compressed,
            String summaryContent,
            List<Message> toDelete
    ) {}
}
