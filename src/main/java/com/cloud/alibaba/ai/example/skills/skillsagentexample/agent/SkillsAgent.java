package com.cloud.alibaba.ai.example.skills.skillsagentexample.agent;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.agent.hook.shelltool.ShellToolAgentHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.skills.SkillsInterceptor;
import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.model.entity.ModelProvider;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.model.service.ModelProviderService;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.ToolRegistry;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.trace.ChatSessionContext;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.trace.ChatTraceAgentHook;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.trace.ChatTraceModelHook;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.trace.ProcessLogCollector;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.trace.ProcessLogEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

/**
 * SkillsAgent 是项目的核心 Agent 编排层。
 * <p>
 * 它基于 Spring AI Alibaba 的 {@link ReactAgent} 构建，集成了：
 * <ul>
 *   <li>SkillsInterceptor：自动加载 runtime/skills/ 目录下的技能</li>
 *   <li>ToolRegistry：注册文件、Shell、HTTP 等工具</li>
 *   <li>ShellToolAgentHook：增强 Shell 工具调用</li>
 *   <li>系统提示词：从 PromptTemplateManager 加载，定义 Agent 角色与行为边界</li>
 * </ul>
 */
@Service
public class SkillsAgent {
    private static final Logger logger = LoggerFactory.getLogger(SkillsAgent.class);
    public static final String SKILLS_DIR = "runtime/skills";

    private final ChatModel fallbackChatModel;
    private final ModelProviderService providerService;
    private final ToolRegistry toolRegistry;
    private final PromptTemplateManager promptTemplateManager;
    private final PlatformDetector platformDetector;
    private final ProcessLogCollector processLogCollector;
    private final boolean preferProvider;
    private final int recursionLimit;
    private final int modelRetryMaxAttempts;
    private final Duration modelRetryDelay;

    private volatile ReactAgent agent;
    private volatile SkillRegistry skillRegistry;
    private volatile String activeProviderId;

    public SkillsAgent(ChatModel fallbackChatModel,
                       ModelProviderService providerService,
                       @Lazy ToolRegistry toolRegistry,
                       PromptTemplateManager promptTemplateManager,
                       PlatformDetector platformDetector,
                       ProcessLogCollector processLogCollector,
                       @Value("${agent.model.prefer-provider:true}") boolean preferProvider,
                       @Value("${agent.recursion-limit:2147483647}") int recursionLimit,
                       @Value("${agent.model.retry.max-attempts:3}") int modelRetryMaxAttempts,
                       @Value("${agent.model.retry.delay-ms:60000}") long modelRetryDelayMs) {
        this.fallbackChatModel = fallbackChatModel;
        this.providerService = providerService;
        this.toolRegistry = toolRegistry;
        this.promptTemplateManager = promptTemplateManager;
        this.platformDetector = platformDetector;
        this.processLogCollector = processLogCollector;
        this.preferProvider = preferProvider;
        this.recursionLimit = recursionLimit;
        this.modelRetryMaxAttempts = Math.max(1, modelRetryMaxAttempts);
        this.modelRetryDelay = Duration.ofMillis(modelRetryDelayMs);
    }

    public synchronized ReactAgent getAgent() {
        ensureModelUpToDate();
        if (agent == null) {
            agent = buildAgent();
        }
        return agent;
    }

    /**
     * 检测当前激活供应商是否变化，若变化则重建 Agent，实现「切换供应商即热生效」。
     */
    private void ensureModelUpToDate() {
        if (!preferProvider) {
            return;
        }
        Optional<ModelProvider> active = providerService.getActiveProviderOpt();
        String currentId = active.map(ModelProvider::getProviderId).orElse(null);
        if (!currentId.equals(activeProviderId)) {
            if (agent != null) {
                logger.info("Active provider changed: {} -> {}, rebuilding agent", activeProviderId, currentId);
                agent = buildAgent();
            }
            activeProviderId = currentId;
        }
    }

    public synchronized void reload() {
        logger.info("Reloading skill registry and rebuilding agent");
        agent = buildAgent();
        activeProviderId = preferProvider
                ? providerService.getActiveProviderOpt().map(ModelProvider::getProviderId).orElse(null)
                : null;
    }

    /** 手动刷新模型（供应商切换后主动调用）。 */
    public synchronized void refreshModel() {
        agent = null;
        ensureModelUpToDate();
        getAgent();
    }

    public List<SkillMetadata> listSkills() {
        getAgent();
        if (skillRegistry == null) {
            return List.of();
        }
        return skillRegistry.listAll();
    }

    public AssistantMessage chat(List<Message> messages) {
        try {
            return getAgent().call(messages);
        } catch (GraphRunnerException e) {
            String reason = e.getMessage() == null ? "" : e.getMessage();
            if (reason.contains("recursion") || reason.contains("limit") || reason.contains("iteration")) {
                throw new IllegalStateException(
                        "Agent 回答超出最大推理轮次（recursion-limit=" + recursionLimit
                                + "）。Agent 目标导向应尽量把任务做完，若被截断说明任务复杂度超出预期，请简化问题后重试",
                        e);
            }
            throw new IllegalStateException("Agent call failed", e);
        }
    }

    public Flux<Message> chatStream(List<Message> messages) {
        return chatStream(messages, null);
    }

    /**
     * 流式对话，同时把指定 sessionId 写入 {@link ChatSessionContext}，
     * 供过程追踪 Hook/Interceptor 路由到正确的会话桶。
     * <p>
     * 当模型调用因瞬态错误（网络超时、HTTP 5xx/429、连接拒绝等）失败时，
     * 自动等待 {@code agent.model.retry.delay-ms}（默认 1 分钟）后重试，
     * 最多 {@code agent.model.retry.max-attempts} 次。每次重试前通过
     * {@link ProcessLogCollector} 追加一条 RETRYING 条目，前端过程日志面板
     * 实时渲染"模型响应超时/报错，正在重试中…"。逻辑错误（提示词/参数/4xx）
     * 不触发重试，直接向上抛出。
     */
    public Flux<Message> chatStream(List<Message> messages, String sessionId) {
        try {
            RunnableConfig config = RunnableConfig.builder()
                    .threadId(sessionId == null || sessionId.isBlank() ? "_default_" : sessionId)
                    .build();
            Flux<Message> base = getAgent().streamMessages(messages, config);

            // 包一层瞬态错误自动重试；逻辑错误透传
            AtomicInteger attempt = new AtomicInteger(0);
            Flux<Message> retried = base
                    .onErrorResume(err -> {
                        if (!isTransientError(err) || attempt.get() >= modelRetryMaxAttempts) {
                            return Flux.error(err);
                        }
                        int next = attempt.incrementAndGet();
                        logger.warn("Model stream transient error (attempt {}/{}), will retry after {}ms: {}",
                                next, modelRetryMaxAttempts, modelRetryDelay.toMillis(), err.getMessage());
                        if (sessionId != null && !sessionId.isBlank()) {
                            int delaySecs = (int) (modelRetryDelay.toMillis() / 1000L);
                            processLogCollector.append(sessionId,
                                    ProcessLogEntry.retrying("skill-agent", next,
                                            delaySecs, describeError(err)));
                        }
                        // 等待 1 分钟（boundedElastic 调度，不阻塞 reactor 线程）
                        // 然后用 base 重新订阅（冷流，重新触发 graph 执行）
                        return Mono.delay(modelRetryDelay, Schedulers.boundedElastic()).thenMany(base);
                    });

            return Flux.defer(() -> {
                        ChatSessionContext.set(sessionId);
                        return retried;
                    })
                    .doFinally(sig -> {
                        if (sig == SignalType.ON_COMPLETE || sig == SignalType.ON_ERROR) {
                            ChatSessionContext.clear();
                        }
                    });
        } catch (GraphRunnerException e) {
            throw new IllegalStateException("Agent stream failed", e);
        }
    }

    /** 判断异常是否属于"瞬态"（值得重试）：超时 / 连接 / 5xx / 429。
     *  对上层（如 ChatController）暴露，避免重复实现。 */
    public static boolean isTransientError(Throwable err) {
        for (Throwable t = err; t != null; t = t.getCause()) {
            if (t instanceof java.net.SocketTimeoutException
                    || t instanceof java.net.http.HttpTimeoutException
                    || t instanceof java.io.InterruptedIOException
                    || t instanceof ResourceAccessException
                    || t instanceof java.net.ConnectException
                    || t instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            if (t instanceof WebClientResponseException webEx) {
                int status = webEx.getStatusCode().value();
                if (status == 429 || status >= 500 && status < 600) {
                    return true;
                }
                return false; // 其他 4xx 视为逻辑错误
            }
        }
        return false;
    }

    /** 给过程日志一条简短描述，方便用户在前端看到"重试原因"。
     *  对上层暴露，复用同一摘要口径。 */
    public static String describeError(Throwable err) {
        String m = err.getMessage();
        if (m == null || m.isBlank()) m = err.getClass().getSimpleName();
        return m.length() > 120 ? m.substring(0, 120) + "…" : m;
    }

    /** 暴露当前配置的最大重试次数（供 ChatController 等上层在错误文案里提示用户）。 */
    public int modelRetryMaxAttempts() {
        return modelRetryMaxAttempts;
    }

    private synchronized ReactAgent buildAgent() {
        Path skillsPath = Path.of(SKILLS_DIR).toAbsolutePath();
        logger.info("Skills directory: {}", skillsPath);

        if (!Files.exists(skillsPath)) {
            logger.error("Skills directory not found at: {}", skillsPath);
            throw new IllegalStateException("Skills directory not found");
        }

        logger.info("Skills directory exists, listing contents:");
        try {
            Files.list(skillsPath).forEach(p ->
                logger.info("  - {}", p.getFileName())
            );
        } catch (IOException e) {
            logger.error("Failed to list directory", e);
        }

        skillRegistry = FileSystemSkillRegistry.builder()
            .userSkillsDirectory(skillsPath.toString())
            .autoLoad(true)
            .build();

        logger.info("Skills loaded: {}", skillRegistry.size());

        SkillsInterceptor interceptor = SkillsInterceptor.builder()
            .skillRegistry(skillRegistry)
            .build();

        List<ToolCallback> tools = toolRegistry.toToolCallbacks();
        logger.info("Agent will use {} tools: {}", tools.size(), toolRegistry.getAllToolNames());

        ShellToolAgentHook hook = ShellToolAgentHook.builder()
            .shellToolName("shell")
            .build();

        // 思考过程追踪：普通聊天也需要把 Agent/模型/工具步骤推到前端（类 kimi 折叠面板）
        ChatTraceAgentHook traceAgentHook = new ChatTraceAgentHook(processLogCollector);
        ChatTraceModelHook traceModelHook = new ChatTraceModelHook(processLogCollector);

        // 从 PromptTemplateManager 加载系统提示词，定义 Agent 角色、能力边界、输出契约
        String systemPrompt = promptTemplateManager.getContentOrDefault(
            "agent-system-prompt",
            "你是一个具备工具调用能力的智能助手。"
        ) + platformDetector.buildPromptHint();
        logger.info("Agent system prompt loaded, length={}", systemPrompt.length());

        CompileConfig compileConfig = CompileConfig.builder()
            .recursionLimit(recursionLimit)
            .build();

        return ReactAgent.builder()
            .name("skill-agent")
            .model(resolveChatModel())
            .systemPrompt(systemPrompt)
            .hooks(hook, traceAgentHook, traceModelHook)
            .interceptors(interceptor)
            .tools(tools)
            .compileConfig(compileConfig)
            .enableLogging(true)
            .build();
    }

    /**
     * 按「当前激活供应商」动态构建 OpenAI 兼容 ChatModel；
     * 无激活供应商（或关闭 prefer-provider）时回退到静态兜底模型。
     */
    private ChatModel resolveChatModel() {
        if (!preferProvider) {
            logger.info("Using fallback static ChatModel (prefer-provider=false)");
            return fallbackChatModel;
        }
        Optional<ModelProvider> active = providerService.getActiveProviderOpt();
        if (active.isEmpty()) {
            logger.warn("No active model provider, falling back to static ChatModel");
            return fallbackChatModel;
        }
        ModelProvider p = active.get();
        ChatModel model = OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder()
                        .apiKey(p.getApiKey())
                        .baseUrl(p.resolveApiBase())
                        .completionsPath("/chat/completions")
                        .embeddingsPath("/embeddings")
                        .build())
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(p.getDefaultModel())
                        .build())
                .build();
        activeProviderId = p.getProviderId();
        logger.info("Building ChatModel for provider '{}' (baseUrl={}, model={})",
                p.getProviderId(), p.getBaseUrl(), p.getDefaultModel());
        return model;
    }
}