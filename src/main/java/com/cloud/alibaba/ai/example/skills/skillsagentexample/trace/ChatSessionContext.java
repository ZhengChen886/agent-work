package com.cloud.alibaba.ai.example.skills.skillsagentexample.trace;

/**
 * 普通聊天流式的会话上下文。
 * <p>
 * {@code SkillsAgent} 是单例共享的 ReactAgent，工具拦截器在调用时拿不到
 * {@link com.alibaba.cloud.ai.graph.RunnableConfig}，因此用一个 ThreadLocal
 * 在每次流式执行期间携带当前 conversationId，供工具/模型过程追踪路由到正确的
 * 会话桶（{@link ProcessLogCollector#append}）。
 */
public final class ChatSessionContext {

    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();

    private ChatSessionContext() {
    }

    public static void set(String sessionId) {
        SESSION_ID.set(sessionId);
    }

    public static String get() {
        return SESSION_ID.get();
    }

    public static void clear() {
        SESSION_ID.remove();
    }
}