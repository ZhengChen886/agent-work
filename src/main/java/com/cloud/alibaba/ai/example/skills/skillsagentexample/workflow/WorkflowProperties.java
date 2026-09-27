package com.cloud.alibaba.ai.example.skills.skillsagentexample.workflow;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 多 Agent 工作流目录配置。
 *
 * <pre>
 * workflow:
 *   agents-dir: runtime/agents        # Agent 定义目录
 *   workflows-dir: runtime/workflows  # 工作流定义目录
 *   auto-load: true           # 是否在启动时加载
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "workflow")
public class WorkflowProperties {

    private String agentsDir = "runtime/agents";
    private String workflowsDir = "runtime/workflows";
    private boolean autoLoad = true;

    public String getAgentsDir() {
        return agentsDir;
    }

    public void setAgentsDir(String agentsDir) {
        this.agentsDir = agentsDir;
    }

    public String getWorkflowsDir() {
        return workflowsDir;
    }

    public void setWorkflowsDir(String workflowsDir) {
        this.workflowsDir = workflowsDir;
    }

    public boolean isAutoLoad() {
        return autoLoad;
    }

    public void setAutoLoad(boolean autoLoad) {
        this.autoLoad = autoLoad;
    }
}