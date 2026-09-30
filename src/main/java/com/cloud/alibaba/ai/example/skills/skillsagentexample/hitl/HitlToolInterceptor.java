package com.cloud.alibaba.ai.example.skills.skillsagentexample.hitl;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HITL 人工审批拦截器：在工具真正执行前拦截，命中审批清单则暂停等待人工决策。
 *
 * <p>挂载方式与 {@code ChatToolTraceInterceptor} 一致：通过
 * {@code AgentHook#getToolInterceptors()} 注入 Agent，普通聊天与工作流
 * sub-Agent 均覆盖。会话 ID 由构造时传入的 {@link Supplier} 动态解析
 * （聊天路径取自 {@code ChatSessionContext}，工作流路径为固定 sessionId）。</p>
 *
 * <p>决策语义（fail-safe）：
 * <ul>
 *   <li>批准 → 放行原调用</li>
 *   <li>拒绝 → 返回带理由的错误响应，LLM 可据此调整策略</li>
 *   <li>超时 / 无法定位会话 / 请求创建失败 → 一律拒绝执行</li>
 * </ul></p>
 */
public class HitlToolInterceptor extends ToolInterceptor {

    private static final Logger log = LoggerFactory.getLogger(HitlToolInterceptor.class);

    private final HitlManager hitlManager;
    private final HitlProperties properties;
    private final Supplier<String> sessionIdSupplier;

    public HitlToolInterceptor(HitlManager hitlManager,
                               HitlProperties properties,
                               Supplier<String> sessionIdSupplier) {
        this.hitlManager = hitlManager;
        this.properties = properties;
        this.sessionIdSupplier = sessionIdSupplier;
    }

    @Override
    public String getName() {
        return "HitlToolInterceptor";
    }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        String toolName = request.getToolName();

        // 未启用 / 豁免清单 / 未命中审批清单：直接放行
        if (!properties.isEnabled() || !properties.requiresApprovalFor(toolName)) {
            return handler.call(request);
        }

        String sessionId = sessionIdSupplier.get();
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("[hitl] no session context for tool={}, rejecting (fail-safe)", toolName);
            return ToolCallResponse.error(request.getToolCallId(), toolName,
                    "HITL：无法定位当前会话，为安全起见拒绝执行工具 " + toolName
                            + "。可调整 hitl.requires-approval 配置。");
        }

        HitlRequest hitlRequest;
        try {
            hitlRequest = hitlManager.createRequest(
                    sessionId, toolName, request.getArguments(), "skill-agent");
        } catch (Exception e) {
            log.error("[hitl] create request failed: tool={}, err={}", toolName, e.getMessage());
            return ToolCallResponse.error(request.getToolCallId(), toolName,
                    "HITL：审批请求创建失败（" + e.getMessage() + "），已拒绝执行。");
        }

        Optional<HitlDecision> decision = hitlManager.awaitDecision(
                hitlRequest.getId(), Duration.ofSeconds(properties.getTimeoutSeconds()));

        if (decision.isEmpty()) {
            return ToolCallResponse.error(request.getToolCallId(), toolName,
                    "人工审批超时（" + properties.getTimeoutSeconds() + "s）未获批准，已拒绝执行 "
                            + toolName + "。请勿原样重试该操作。");
        }
        if (decision.get().action() == HitlDecision.Action.REJECT) {
            String note = decision.get().note();
            return ToolCallResponse.error(request.getToolCallId(), toolName,
                    "人工审批已拒绝执行 " + toolName
                            + (note == null || note.isBlank() ? "" : "，理由：" + note)
                            + "。请改用其他方案。");
        }
        log.info("[hitl] approved, executing tool={}", toolName);
        return handler.call(request);
    }
}
