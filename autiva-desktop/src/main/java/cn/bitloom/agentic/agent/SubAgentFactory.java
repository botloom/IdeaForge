package cn.bitloom.agentic.agent;

import cn.bitloom.agentic.agent.interceptor.AgentMemoryInterceptor;
import cn.bitloom.agentic.agent.interceptor.AgentMemoryRecallInterceptor;
import cn.bitloom.agentic.agent.interceptor.EnvironmentContextInterceptor;
import cn.bitloom.agentic.agent.interceptor.FileChangeRecorderInterceptor;
import cn.bitloom.agentic.agent.interceptor.PermissionInterceptor;
import cn.bitloom.agentic.agent.interceptor.SessionMemoryInterceptor;
import cn.bitloom.agentic.agent.interceptor.SkillContextInterceptor;
import cn.bitloom.agentic.agent.interceptor.ToolCallBudgetInterceptor;
import cn.bitloom.agentic.agent.interceptor.ToolCardEventInterceptor;
import cn.bitloom.agentic.agent.interceptor.ToolResultOffloadInterceptor;
import cn.bitloom.agentic.skill.SkillManager;
import cn.bitloom.agentic.memory.FileSystemAgentMemoryStore;
import cn.bitloom.agentic.memory.MemoryConsolidator;
import cn.bitloom.agentic.model.ModelFactory;
import cn.bitloom.agentic.session.EventFilter;
import cn.bitloom.agentic.session.FileSystemSessionManager;
import cn.bitloom.agentic.session.MessageFilter;
import cn.bitloom.agentic.session.Session;
import cn.bitloom.agentic.session.compaction.TokenCountCompactionStrategy;
import cn.bitloom.agentic.session.compaction.TokenCountTrigger;
import cn.bitloom.agentic.tool.Toolkit;
import cn.bitloom.agentic.tool.command.ShellSession;
import cn.bitloom.agentic.tool.session.ConversationSearchTool;
import cn.bitloom.agentic.tool.session.CrossSessionSearchTool;
import cn.bitloom.agentic.permission.strategy.ToolApprovalStrategy;
import cn.bitloom.config.ConfigManager;
import cn.bitloom.constant.AppConstants;
import cn.bitloom.exception.AgentException;
import cn.bitloom.harness.llm.ChatModel;
import cn.bitloom.harness.llm.Role;
import cn.bitloom.harness.llm.TokenCountEstimator;
import cn.bitloom.harness.loop.LoopInterceptor;
import cn.bitloom.harness.tool.ToolCallback;
import cn.bitloom.store.Store;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 子智能体构建工厂 — TaskTool（一次性委派）/ TeammateRuntime（持久队友）/
 * WorkflowContext（编排原语）共用的 Agent 构建逻辑。
 *
 * <p>统一模式：复用父 Session + branch 事件隔离 + 四步压缩管线 + reactive_compact
 * + 记忆自动化拦截器。
 */
public class SubAgentFactory {

    private final FileSystemSessionManager sessionManager;
    private final AgentDefinitionManager definitionManager;
    private final ModelFactory modelFactory;
    private final Supplier<Toolkit> toolkitProvider;
    private final SkillManager skillManager;
    private final List<ToolApprovalStrategy> approvalStrategies;
    private final ConfigManager configManager;

    public SubAgentFactory(FileSystemSessionManager sessionManager,
            AgentDefinitionManager definitionManager,
            ModelFactory modelFactory,
            Supplier<Toolkit> toolkitProvider,
            SkillManager skillManager,
            List<ToolApprovalStrategy> approvalStrategies,
            ConfigManager configManager) {
        this.sessionManager = sessionManager;
        this.definitionManager = definitionManager;
        this.modelFactory = modelFactory;
        this.toolkitProvider = toolkitProvider;
        this.skillManager = skillManager;
        this.approvalStrategies = approvalStrategies;
        this.configManager = configManager;
    }

    /**
     * 构建子智能体 Agent。
     *
     * @param parentSession       父会话（事件写入目标）
     * @param agentId             AgentDefinition 名
     * @param branch              事件隔离 branch（如 subagent.xxx / teammate.xxx / workflow.xxx）
     * @param projectPath         工具上下文 projectPath（可 null）
     * @param systemPromptOverride 自定义系统提示（null = definition.content() + envBlock）
     * @param extraTools          追加工具（团队协作工具等；空 = 仅白名单 + 会话搜索）
     */
    public Agent build(Session parentSession, String agentId, String branch, String projectPath,
            String systemPromptOverride, List<ToolCallback> extraTools) {
        AgentDefinition definition = definitionManager.getDefinition(agentId);
        if (definition == null) {
            throw AgentException.subagentNotFound("子智能体定义不存在: " + agentId
                    + "，可用定义: " + definitionManager.getSubagentDefinitions().stream()
                            .map(AgentDefinition::name).toList());
        }
        ChatModel chatModel = modelFactory.model(Store.selectedModel.get());
        String uid = parentSession.userId() != null ? parentSession.userId() : "default-user";

        List<LoopInterceptor> interceptors = new ArrayList<>();

        // 纯 token 压缩：DS 上下文 1M，达到 80%（800k token）时触发，压缩到约 60%（480k token）
        TokenCountCompactionStrategy tokenStrategy = TokenCountCompactionStrategy.builder()
                .maxTokens(480000)
                .build();

        // EventFilter.forBranch(branch): 子智能体仅能看到自己 branch 的事件 + root 事件。
        // SessionMemoryInterceptor 也会按 ctx.branch() 自动合并 branch 过滤，此处显式传入
        // 保证即使 branch 透传缺失也能正确隔离。
        SessionMemoryInterceptor sessionMemoryInterceptor = SessionMemoryInterceptor.builder(sessionManager)
                .defaultUserId(uid)
                .eventFilter(EventFilter.forBranch(branch))
                .messageFilter(MessageFilter.byMessageType(Role.USER, Role.ASSISTANT, Role.TOOL)
                        .and(MessageFilter.skipEmptyMessages()))
                .compactionTrigger(TokenCountTrigger.builder()
                        .threshold(800000)
                        .tokenCountEstimator(new TokenCountEstimator())
                        .build())
                .compactionStrategy(tokenStrategy)
                .build();
        interceptors.add(sessionMemoryInterceptor);

        Path memoriesDir = resolveMemoriesDir(parentSession.id());
        FileSystemAgentMemoryStore memoryStore = new FileSystemAgentMemoryStore(memoriesDir);
        interceptors.add(AgentMemoryInterceptor.builder()
                .memoryStore(memoryStore)
                .memoriesRootDirectory(memoriesDir.toString())
                .memoryConsolidationTrigger(
                        MemoryConsolidator.triggerWhen(memoryStore, MemoryConsolidator.DEFAULT_THRESHOLD))
                .build());
        interceptors.add(AgentMemoryRecallInterceptor.builder()
                .sessionManager(sessionManager)
                .memoryStore(memoryStore)
                .chatModel(chatModel)
                .build());
        interceptors.add(SkillContextInterceptor.builder().skillManager(skillManager).build());
        interceptors.add(EnvironmentContextInterceptor.builder().build());

        List<ToolCallback> allTools = new ArrayList<>(toolkitProvider.get().buildToolCallbacks(definition));
        allTools.add(ConversationSearchTool.builder(sessionManager).build().toToolCallback());
        allTools.add(CrossSessionSearchTool.builder(sessionManager, uid).build().toToolCallback());
        if (extraTools != null) {
            allTools.addAll(extraTools);
        }

        Agent agent = Agent.builder()
                .name(agentId)
                .definition(definition)
                .model(chatModel)
                .systemPrompt(systemPromptOverride != null ? systemPromptOverride
                        : definition.content() + ShellSession.envBlock())
                .tools(allTools)
                .interceptors(mergeInterceptors(interceptors, buildBaseInterceptors()))
                // reactive_compact：上下文超长被 API 拒绝时强制压缩（绕过触发器）后重试一次
                .reactiveCompactor(sid -> sessionManager.compact(sid, req -> true, tokenStrategy))
                .build();
        return agent;
    }

    private Path resolveMemoriesDir(String parentSessionId) {
        String[] parts = parentSessionId.split("-", 3);
        String mode = parts[0];
        if ("code".equals(mode)) {
            String projectName = parts.length > 1 ? parts[1] : null;
            if (projectName == null || projectName.isBlank()) {
                throw new IllegalStateException("code 模式 sessionId 必须包含 projectName: " + parentSessionId);
            }
            return AppConstants.Memory.projectMemoryDir(projectName);
        }
        return AppConstants.Memory.workMemoryDir();
    }

    /**
     * 基础拦截器集：预算保护 / 权限审批 / 文件快照 / 工具结果落盘 / 工具卡片事件
     * （供 TaskCard 展示工具调用）。每次构建 Agent 都 new 新实例（内部持有 per-session
     * 可变状态，避免多智能体共享串扰）。
     * 注意：不挂 TodoReminderInterceptor——子智能体未提供 TodoWrite 工具，提醒反而误导。
     */
    private List<LoopInterceptor> buildBaseInterceptors() {
        List<LoopInterceptor> interceptors = new ArrayList<>();
        interceptors.add(new ToolCallBudgetInterceptor(configManager.getMaxToolCalls()));
        interceptors.add(new PermissionInterceptor(approvalStrategies));
        interceptors.add(new FileChangeRecorderInterceptor());
        interceptors.add(new ToolResultOffloadInterceptor());
        interceptors.add(new ToolCardEventInterceptor());
        return interceptors;
    }

    /** 组装拦截器：记忆/上下文拦截器（高 order）在前，基础工具拦截器（低 order）在后。 */
    private List<LoopInterceptor> mergeInterceptors(List<LoopInterceptor> head, List<LoopInterceptor> tail) {
        List<LoopInterceptor> merged = new ArrayList<>(head);
        merged.addAll(tail);
        return merged;
    }
}
