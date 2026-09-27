# AgentWork

基于 **Spring AI Alibaba Agent Framework** 的企业级 AI Agent 工作台：一个可插拔的 **Skills 技能系统** + **多 Agent 工作流编排** + **全链路可观测性**的完整落地参考。

> Java 21 · Spring Boot 3.5.8 · Spring AI 1.1.2 · Spring AI Alibaba 1.1.2.0 · SQLite · WebFlux SSE

## ✨ 核心特性

| 特性 | 说明 |
|---|---|
| 🤖 ReAct Agent | 基于 `ReactAgent` 构建，自动加载 `skills/` 目录技能，支持文件/Shell/HTTP 等工具调用 |
| 📦 Skills 技能系统 | 文件驱动（`SKILL.md` + 脚本），支持 ZIP 上传安装、热加载、按 Agent 挂载/卸载 |
| 🔗 多 Agent 工作流 | JSON 节点-边定义，文件驱动 + 热加载，SSE 流式执行，一次编排 4 个专职 Agent 协作 |
| 🌊 流式对话 | SSE JSON 事件协议，前端 Markdown 流式渲染，断线降级同步接口 |
| 🔍 全链路可观测 | 统一 trace 汇聚 → SSE 实时调试面板 + **SQLite 持久化（保留 7 天）**，Prometheus 指标 + Token 成本估算 |
| 🛡️ 安全防护 | 敏感词拦截、小模型语义解读、文件沙箱、Shell 命令黑名单、Admin 后台 Basic Auth |
| 💾 会话持久化 | SQLite 存储会话/消息，上下文自动压缩摘要（SUMMARY 角色），Prompt 模板版本化 + 回滚 |

## 🏗️ 架构总览

```
                        ┌─────────────────────────────────────────┐
   浏览器 /index.html   │            ChatController (SSE)          │
   ┌──────────────┐    │  /api/chat-stream                        │
   │ 聊天 + 调试面板 │───▶│  · PromptPreprocessor 规则/敏感词拦截    │
   └──────────────┘    │  · SemanticInterpreter 小模型语义解读     │
                        │  · ContextCompressor 历史压缩            │
                        └───────────────┬─────────────────────────┘
                                        │
                        ┌───────────────▼─────────────────────────┐
                        │      SkillsAgent (ReactAgent)            │
                        │  · SkillsInterceptor ← skills/ 目录      │
                        │  · ToolRegistry（file/shell/http 工具）    │
                        │  · SandboxPolicy 沙箱校验                │
                        └───────────────┬─────────────────────────┘
                                        │ /workflow 命令
                        ┌───────────────▼─────────────────────────┐
                        │      WorkflowService（多 Agent 编排）     │
                        │  agents/ → author → hexo-generator      │
                        │           → blog-assembler → git-publisher│
                        └───────────────┬─────────────────────────┘
                                        │ Hook/Interceptor 写入
                        ┌───────────────▼─────────────────────────┐
                        │   ProcessLogCollector（trace 唯一汇聚点）   │
                        │  · 内存缓冲 → SSE process_log 实时推送     │
                        │  · 单点落库 → trace_logs 表（保留 7 天）    │
                        │  · TTL 清理：内存 24h / SQLite 7 天       │
                        └───────────────────────────────────────────┘
   管理端 8088 /admin   AdminServer（独立端口 + Basic Auth）
```

## 🚀 快速开始

### 环境要求

- JDK 21、Maven 3.9+
- 一个 OpenAI 兼容 API Key（默认对接 ModelScope）

### 1. 配置模型

```powershell
# PowerShell 环境变量（或写入系统环境变量）
$env:MODELSCOPE_API_KEY = "sk-xxx"          # 必填
# 可选覆盖：
$env:OPENAI_BASE_URL = "https://api-inference.modelscope.cn/v1"
$env:OPENAI_MODEL = "deepseek-ai/DeepSeek-V4-Pro-0813"
```

### 2. 启动

```powershell
# 方式一：Windows 一键脚本（自动清理 8080/8088 端口后启动）
.\start.bat

# 方式二：Maven 直跑
mvn spring-boot:run

# 方式三：打包运行
mvn package -DskipTests
java -jar target/agentwork-0.0.1-SNAPSHOT.jar
```

### 3. 访问入口

| 入口 | 地址 | 说明 |
|---|---|---|
| 用户端聊天 UI | http://localhost:8080 | 会话管理 + Markdown 流式渲染 + 🔍调试面板 |
| Prompt 模板管理 | http://localhost:8080/templates.html | 模板编辑 / 历史版本 / 回滚 |
| Admin 管理后台 | http://localhost:8088/admin/index.html | Basic Auth（admin / admin123），技能/Agent/工作流管理 |
| Prometheus 指标 | http://localhost:8080/actuator/prometheus | 工具调用计数、耗时、Token 成本 |

## 📡 REST API 一览

### 对话与会话

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/chat-stream` | 流式对话（SSE），body: `{message, conversationId}` |
| GET | `/api/chat-stream?message=` | 同上（向后兼容） |
| POST | `/api/chat` | 同步对话（SSE 失败时降级用） |
| POST/GET | `/api/conversations` | 创建 / 列出会话 |
| GET | `/api/conversations/{id}` | 会话消息历史 |
| DELETE | `/api/conversations/{id}` | 删除会话（联动清理 trace） |

消息以 `/workflow <name> <input>` 开头时，自动转入工作流执行。

### Skills / Agents / Workflows / Tools

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/skills` | 列出已安装技能 |
| POST | `/api/skills/upload` | 上传 ZIP 安装技能（大小/条目数/路径穿越校验） |
| DELETE | `/api/skills/{name}` | 卸载技能 |
| GET/POST | `/api/agents` | 列出 / 创建 Agent（`agents/<name>/agent.json`） |
| GET/PUT/DELETE | `/api/agents/{name}` | 查看详情 / 更新 / 删除 |
| POST | `/api/agents/reload` | 热加载全部 Agent |
| GET/POST | `/api/agents/{name}/skills` | 查看挂载技能 / 挂载技能 |
| DELETE | `/api/agents/{name}/skills/{skillName}` | 卸载技能 |
| GET | `/api/workflows` / `/api/workflows/agents` | 工作流 / 可用 Agent 列表 |
| GET | `/api/workflows/{name}` | 工作流定义详情（节点+边） |
| POST | `/api/workflows` | 保存工作流定义（前端画布编排） |
| POST | `/api/workflows/{name}/run` | **SSE 流式执行工作流** |
| POST/DELETE | `/api/workflows/reload` · `/{name}` | 热加载 / 删除工作流 |
| GET | `/api/tools` · `/api/tools/categories` | 工具清单 / 分类 |
| POST | `/api/tools/{name}/invoke` | 直接调用工具（调试用） |

### Prompt 模板 / 安全配置

| 方法 | 路径 | 说明 |
|---|---|---|
| GET/PUT | `/api/templates` | 模板列表 / 保存新版本（乐观锁） |
| GET | `/api/templates/{name}/history` | 版本历史 |
| POST | `/api/templates/{name}/restore/{versionId}` | 回滚到指定版本 |
| GET/PUT | `/api/preprocessor` | 敏感词表 / 命令前缀配置 |

### Trace 可观测查询（新增）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/traces/sessions` | 最近有 trace 的会话列表（最新优先，最多 100 个） |
| GET | `/api/traces/{sessionId}` | 单会话完整调用链明细（时间升序，7 天内） |

## 🌊 SSE 事件协议

`/api/chat-stream` 与 `/api/workflows/{name}/run` 均输出 `text/event-stream`：

```
data: {"type":"assistant","content":"<累积全文>"}     # 流式 token（前端整体重渲 Markdown）
data: {"type":"process_log","step":"TOOL_CALL","toolName":"web.search",...}   # 工作流过程日志
data: {"type":"debug:rule_check","duration_ms":12,...}  # 调试模式下的预处理事件
data: {"type":"debug:semantic","intent":"search",...}
data: {"type":"finish","answer":"<最终全文>","conv_id":"..."}
data: {"type":"done","conv_id":"..."}                 # 结束帧
data: {"type":"error","error":"..."}                  # 错误帧
```

前端 `index.html` 内置 **🔍 调试面板**：时间线（✓/⟳/✗ 状态点 + 耗时）、工具调用卡片、指标栏（总耗时 / Token / 工具调用数）、实时日志滚动。

## 🔍 可观测性设计

**单一汇聚点**：所有 trace 写入方（workflow 的 Agent/Model Hook、Tool 拦截器、`BaseTool`、聊天预处理事件）统一走 `ProcessLogCollector.append()`，在这一处完成：

1. **内存缓冲** → SSE 订阅者实时推送（`process_log` 事件）
2. **SQLite 落库** → `trace_logs` 表（写库失败仅告警，不影响主链路）
3. **生命周期**：内存 TTL 24 小时；SQLite 保留 `agent.debug.trace-retention-days`（默认 7 天），每小时由清理任务滚动删除；删除会话时同步删除其 trace

**指标（Micrometer → Prometheus）**：工具调用总数/成功/失败/按工具细分、Agent 与工具执行耗时、语义解读次数、Prompt 拦截次数、Token 用量与成本估算（DeepSeek-V3 参考单价）。

## ⚙️ 关键配置（application.yml）

```yaml
spring:
  datasource:
    url: jdbc:sqlite:./data/example.db     # SQLite 单文件数据库
  jpa:
    hibernate:
      ddl-auto: update                      # 实体自动建表
  ai:
    openai:
      api-key: ${MODELSCOPE_API_KEY}
      base-url: ${OPENAI_BASE_URL:https://api-inference.modelscope.cn/v1}
      chat:
        options:
          model: ${OPENAI_MODEL:deepseek-ai/DeepSeek-V4-Pro-0813}

sandbox:            # 文件沙箱：允许根目录 + 拒绝模式（.ssh/.git/系统目录）
  file: { roots: [skills, agents, workflows, data, output] }
  shell: { denied-patterns: ["rm -rf /", "format ", ...] }   # Shell 命令黑名单

agent:
  context:          # 上下文管理：32K 窗口、保留 6 轮原文、超限自动摘要
    max-context-tokens: 32768
  debug:            # 可观测性开关
    enabled: true
    trace-retention-hours: 24   # 内存缓冲 TTL
    trace-retention-days: 7      # SQLite trace 保留天数

workflow: { agents-dir: agents, workflows-dir: workflows, auto-load: true }
admin:    { enabled: true, port: 8088, username: admin, password: admin123 }

management:         # Actuator: /actuator/{health,metrics,prometheus}
  endpoints.web.exposure.include: health,info,metrics,prometheus
```

## 📁 项目结构

```
agentwork/
├── skills/                    # 技能库：SKILL.md + scripts/（web-search、arxiv-search...）
├── agents/                    # 多 Agent 定义：agent.json + 本地 skills/（author、git-publisher...）
├── workflows/                # 工作流 JSON：节点(agent ref) + 边（publish-article: 4 Agent 串联）
├── data/                      # SQLite 数据库（example.db：会话/消息/trace_logs/模板版本）
├── output/                    # 工具输出目录（沙箱允许）
└── src/main/
    ├── resources/static/      # index.html（聊天+调试面板）、templates.html、admin/
    └── java/.../skillsagentexample/
        ├── agent/             # SkillsAgent(ReAct)、ContextCompressor、SemanticInterpreter、PromptPreprocessor*
        ├── controller/        # Chat/Skill/Agent/Workflow/Tool/Template/Conversation/Trace/Preprocessor
        ├── trace/             # ProcessLogCollector（统一 trace 汇聚+持久化）、ProcessLogEntry、AgentMetrics
        ├── tool/              # BaseTool、ToolRegistry、WrappedToolCallback、沙箱策略
        ├── workflow/          # 多 Agent 编排 + Trace Hook/Interceptor
        ├── entity/repository/ # Conversation/Message/TraceLogEntry/PromptTemplateVersion
        ├── service/           # 持久化事务、会话、工作流引擎
        ├── security/          # Admin 鉴权（Basic Auth 复用主容器 AuthenticationManager）
        └── admin/             # 独立 8088 端口 Admin 后台
```

## 🧪 测试

```powershell
mvn test        # 33 个测试：上下文加载、技能 ZIP 安全校验、沙箱策略、trace 持久化（append 落库/clear 删库/7 天清理）
mvn verify      # CI 同款命令（.github/workflows/ci.yml）
```

> 测试已配置 `admin.port=0`（随机端口），与正在运行的应用不冲突，可随时执行。
