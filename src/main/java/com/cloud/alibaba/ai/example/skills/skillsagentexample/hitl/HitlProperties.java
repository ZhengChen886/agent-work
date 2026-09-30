package com.cloud.alibaba.ai.example.skills.skillsagentexample.hitl;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Human-in-the-Loop（HITL，人工审批）配置。
 *
 * <p>在 Agent 执行链上为有副作用的工具调用插入强制人工审批关卡：
 * 命中 {@code requires-approval} 的工具在被 Agent 调用前会暂停执行，
 * 推送 HITL_REQUEST 事件到前端轨迹面板，等待人工通过
 * {@code POST /api/hitl/requests/{id}/decide} 批准或拒绝；
 * 超时未决策按拒绝处理（fail-safe）。</p>
 *
 * <pre>
 * hitl:
 *   enabled: true
 *   timeout-seconds: 300
 *   requires-approval:
 *     - shell.exec
 *     - file.write
 *     - file.delete
 *     - http.request
 *     - agent.tool.invoke
 *   auto-approve: []        # 显式豁免清单，优先级高于 requires-approval
 * </pre>
 *
 * <p><b>默认关闭</b>：开启后无人在线审批时，命中清单的工具会阻塞到超时再拒绝，
 * 影响无人值守流程，因此由使用者显式开启。</p>
 */
@Component
@ConfigurationProperties(prefix = "hitl")
public class HitlProperties {

    private static final Logger log = LoggerFactory.getLogger(HitlProperties.class);

    /** 是否启用 HITL 审批关卡。 */
    private boolean enabled = false;

    /** 审批等待超时（秒），超时按拒绝处理。 */
    private int timeoutSeconds = 300;

    /** 需要人工审批的工具名清单（精确匹配）。 */
    private List<String> requiresApproval = new ArrayList<>(List.of(
            "shell.exec", "file.write", "file.delete", "http.request", "agent.tool.invoke"));

    /** 显式豁免清单：在其中的工具直接放行，优先级高于 requires-approval。 */
    private List<String> autoApprove = new ArrayList<>();

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.warn("HITL 人工审批未启用（hitl.enabled=false）。"
                    + "Agent 将无需审批直接执行 shell.exec/file.write 等有副作用的工具；"
                    + "对外部署建议设置 hitl.enabled=true 并配置 requires-approval。");
        } else {
            log.info("HITL 人工审批已启用：requires-approval={}，auto-approve={}，timeout={}s",
                    requiresApproval, autoApprove, timeoutSeconds);
        }
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 300;
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public List<String> getRequiresApproval() { return requiresApproval; }
    public void setRequiresApproval(List<String> requiresApproval) {
        this.requiresApproval = requiresApproval == null ? new ArrayList<>() : requiresApproval;
    }

    public List<String> getAutoApprove() { return autoApprove; }
    public void setAutoApprove(List<String> autoApprove) {
        this.autoApprove = autoApprove == null ? new ArrayList<>() : autoApprove;
    }

    /** 判断指定工具是否需要人工审批。 */
    public boolean requiresApprovalFor(String toolName) {
        if (toolName == null) return false;
        if (autoApprove.contains(toolName)) return false;
        return requiresApproval.contains(toolName);
    }
}
