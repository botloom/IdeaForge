package cn.bitloom.vm;

import cn.bitloom.agentic.agent.Agent;
import cn.bitloom.agentic.agent.AgentDefinition;
import cn.bitloom.agentic.agent.AgentDefinitionManager;
import cn.bitloom.agentic.agent.RuntimeContext;
import cn.bitloom.agentic.agent.advisor.AgentMemoryAdvisor;
import cn.bitloom.agentic.agent.advisor.EnvironmentContextAdvisor;
import cn.bitloom.agentic.agent.advisor.AgentMemoryRecallAdvisor;
import cn.bitloom.agentic.agent.advisor.SessionMemoryAdvisor;
import cn.bitloom.agentic.agent.advisor.SkillContextAdvisor;
import cn.bitloom.agentic.agent.advisor.SubagentContextAdvisor;
import cn.bitloom.agentic.event.AbstractEvent;
import cn.bitloom.agentic.event.CompactionEvent;
import cn.bitloom.agentic.event.MessageEvent;
import cn.bitloom.agentic.event.MemoryRecallEvent;
import cn.bitloom.agentic.event.UICardEvent;
import cn.bitloom.agentic.hook.IAgentHook;
import cn.bitloom.agentic.hook.MemoryExtractionHook;
import cn.bitloom.agentic.hook.PermissionHook;
import cn.bitloom.agentic.hook.TodoReminderHook;
import cn.bitloom.agentic.hook.ToolCallBudgetHook;
import cn.bitloom.agentic.hook.ToolCardEventHook;
import cn.bitloom.agentic.hook.ToolResultOffloadHook;
import cn.bitloom.agentic.memory.FileSystemAgentMemoryStore;
import cn.bitloom.agentic.memory.MemoryConsolidator;
import cn.bitloom.agentic.model.ModelFactory;
import cn.bitloom.agentic.session.*;
import cn.bitloom.agentic.session.compaction.TokenCountCompactionStrategy;
import cn.bitloom.agentic.session.compaction.TokenCountTrigger;
import cn.bitloom.agentic.skill.SkillManager;
import cn.bitloom.agentic.tool.Toolkit;
import cn.bitloom.agentic.tool.plan.ExitPlanModeTool;
import cn.bitloom.agentic.tool.session.ConversationSearchTool;
import cn.bitloom.agentic.tool.session.CrossSessionSearchTool;
import cn.bitloom.node.message.*;
import cn.bitloom.node.tool.ToolCallCard;
import cn.bitloom.store.Store;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 首页 ViewModel 抽象基类。
 * <p>
 * 包含通用的会话管理、消息发送、agent 直接调用逻辑。
 * 子类（CoderHomePageViewModel / WorkHomePageViewModel）实现模式专有逻辑。
 * <p>
 * <b>多 session 并发模型</b>：一个 session 与一个 Agent 实例 1:1 绑定（缓存在
 * {@link #sessionAgents}），同一 session 内的所有消息复用同一 Agent。
 * 每个 session 拥有独立的 {@link SessionRuntimeState}（订阅、流式状态、消息缓存等），
 * 切换活动 session 时原 session 的后台任务不被中断，切回可恢复完整进度。
 * <p>
 * UI 绑定的 {@link #messages} 是单一稳定引用，切换 session 时通过 setAll 替换内容；
 * 非 active session 的事件只更新对应 state 的 savedMessages，不污染 UI。
 * per-session 锁保证同一 session 的串行处理。
 */
@Slf4j
public abstract class AbstractHomePageViewModel {

    /** Task（子智能体）工具名：由 ToolUIBridge 单独渲染 TaskCard，不参与 ToolCallCard 组。 */
    private static final String TASK_TOOL_NAME = "Task";

    /** TodoWrite 工具名：由 ToolUIBridge 单独渲染 TodoCard，且需要闭合 AI 冒泡但同样不创建 ToolCallCard。 */
    private static final String TODO_TOOL_NAME = "TodoWrite";

    protected final FileSystemSessionManager sessionManager;
    protected final AgentDefinitionManager definitionManager;
    protected final ModelFactory modelFactory;
    protected final Toolkit toolkit;
    protected final SkillManager skillManager;
    protected final List<cn.bitloom.agentic.permission.strategy.ToolApprovalStrategy> approvalStrategies;
    protected final cn.bitloom.config.ConfigManager configManager;
    protected final cn.bitloom.agentic.goal.GoalManager goalManager;
    protected final cn.bitloom.bridge.desktop.ToolUIBridge toolUIBridge;

    /** UI 绑定的稳定消息列表引用，切换 session 时通过 setAll 替换内容 */
    @Getter
    private final ObservableList<MessageCard> messages = FXCollections.observableArrayList();

    protected Session session;

    /** per-session Agent 缓存：sessionId → Agent 实例，session 级生命周期，销毁时移除 */
    private final Map<String, Agent> sessionAgents = new ConcurrentHashMap<>();

    /**
     * per-session 运行时状态：订阅、流式状态、流式消息卡片、待响应工具卡片、消息缓存。
     * 切换 session 时仅切换 active 引用，不取消非 active session 的订阅。
     */
    private final Map<String, SessionRuntimeState> sessionStates = new ConcurrentHashMap<>();

    /** 当前活动 session 的运行时状态（UI 显示的就是它的 messages） */
    protected SessionRuntimeState currentState = null;

    /**
     * 历史消息加载状态：prepareHistoricalMessages 期间为 true，加载完成自动置 false。
     * UI 绑定此属性：加载期间禁用发送按钮并显示加载提示。
     */
    private final BooleanProperty historyLoading = new SimpleBooleanProperty(false);

    public BooleanProperty historyLoadingProperty() {
        return historyLoading;
    }

    /** session 切换回调：通知 Controller 重置 todo 卡片等 */
    @Setter
    private Consumer<String> sessionActivatedHandler = _ -> {};

    /**
     * per-session 运行时状态。所有可变状态都放在这里，ViewModel 仅持有当前 active 引用。
     */
    protected static final class SessionRuntimeState {
        /** 当前 agent 流订阅（用于 pause 取消，dispose 经 sink.onCancel 级联取消 LLM 流） */
        Disposable subscription;
        /** 当前流式正文卡片 */
        AssistantMessageCard currentAssistantCard = null;
        /** 当前「思考过程」折叠容器（收容连续思考段与思考引发的工具组） */
        ReasoningProcessCard currentReasoningProcess = null;
        /** 容器内当前一段连续思考的子块（工具轮次后的新思考另起一块） */
        ReasoningCard currentReasoningCard = null;
        /** 当前正在累积的工具调用组卡片（同一轮 AI 话语后的连续工具聚合为一组） */
        cn.bitloom.node.tool.ToolCallCard currentToolGroup = null;
        /** 新一条 AI 话语开始后，工具调用应新起一组 */
        boolean needNewToolGroup = true;
        /** 当前工具组内尚未完成（COMPLETED/FAILED）的工具调用 id */
        final java.util.Set<String> activeToolCallIds = new java.util.HashSet<>();
        /** 是否正在流式生成（per-session） */
        volatile boolean isStreaming = false;
        /** 是否暂停（per-session） */
        volatile boolean isPaused = false;
        /** 切换走时保存的 messages 副本（同一 MessageCard 引用），切回时整体恢复 */
        final List<MessageCard> savedMessages = new ArrayList<>();
    }

    protected AbstractHomePageViewModel(FileSystemSessionManager sessionManager,
                                        AgentDefinitionManager definitionManager,
                                        ModelFactory modelFactory,
                                        Toolkit toolkit,
                                        SkillManager skillManager,
                                        List<cn.bitloom.agentic.permission.strategy.ToolApprovalStrategy> approvalStrategies,
                                        cn.bitloom.config.ConfigManager configManager,
                                        cn.bitloom.agentic.tool.mcp.McpConnectionManager mcpConnectionManager,
                                        cn.bitloom.agentic.goal.GoalManager goalManager,
                                        cn.bitloom.bridge.desktop.ToolUIBridge toolUIBridge) {
        this.sessionManager = sessionManager;
        this.definitionManager = definitionManager;
        this.modelFactory = modelFactory;
        this.toolkit = toolkit;
        this.skillManager = skillManager;
        this.approvalStrategies = approvalStrategies;
        this.configManager = configManager;
        this.goalManager = goalManager;
        this.toolUIBridge = toolUIBridge;
        // MCP 连接变化（McpConnect/断开）→ evict 全部 per-session Agent 缓存，
        // 下一次 sendMessage 经 computeIfAbsent 重建，工具池即含最新 MCP 工具。
        // 正在流式处理中的 Agent 不受影响（引用仍被持有），新工具自下一轮对话生效。
        mcpConnectionManager.addChangeListener(sessionAgents::clear);
    }

    public void createNewSession() {
        // 保存当前 session 的 messages 到 state（不取消订阅，让后台任务继续运行）
        saveMessagesToCurrentState();
        // 切换到 null state（新建会话）
        currentState = null;
        this.session = null;
        Store.currentSessionId.set("");
        messages.clear();
        // 新会话初始为非流式状态
        Store.isStreaming.set(false);
        Store.isPaused.set(false);
        // 通知 Controller 清空 EditorPanel 工具卡片 / todo
        sessionActivatedHandler.accept(null);
    }

    public void switchToSession(String sessionId) {
        if (this.session != null && sessionId.equals(this.session.id())) {
            return;
        }

        Session targetSession = sessionManager.getById(sessionId);
        if (targetSession == null) {
            log.warn("切换到不存在的session: {}", sessionId);
            return;
        }

        // 保存当前 session 的 messages 到 state（不取消订阅）
        saveMessagesToCurrentState();

        this.session = targetSession;
        Store.currentSessionId.set(sessionId);

        boolean stateExisted = sessionStates.containsKey(sessionId);
        currentState = sessionStates.computeIfAbsent(sessionId, k -> new SessionRuntimeState());

        if (stateExisted) {
            // 已有 state：恢复 savedMessages 到 UI，同步流式状态
            messages.setAll(currentState.savedMessages);
            Store.isStreaming.set(currentState.isStreaming);
            Store.isPaused.set(currentState.isPaused);
        } else {
            // 首次切换：清空 UI，同步非流式状态，后续 prepareHistoricalMessages 加载历史
            messages.clear();
            Store.isStreaming.set(false);
            Store.isPaused.set(false);
        }

        // 子类恢复模式专有状态（如 coder 从 metadata 恢复 currentProject）
        onSessionSwitched(targetSession);

        // 通知 Controller 清空 EditorPanel 工具卡片 / todo（非 active session 的工具不显示）
        sessionActivatedHandler.accept(sessionId);

        if (!stateExisted && hasHistoricalMessages()) {
            prepareHistoricalMessages();
        }
    }

    /**
     * 保存当前 messages 内容到 currentState.savedMessages（切换走时调用）。
     * MessageCard 引用保持不变，切回时可直接 setAll 恢复。
     */
    private void saveMessagesToCurrentState() {
        if (currentState != null) {
            currentState.savedMessages.clear();
            currentState.savedMessages.addAll(messages);
        }
    }

    public void switchAgent(String agentId) {
        // 在旧 viewModel 上清理模式专有状态（如 coder 清空 currentProject）
        onSwitchAgent(agentId);
        // 触发 HomePageRouter.switchMode，由它在切换后对新 viewModel 调用 createNewSession
        Store.currentAgent.set(agentId);
    }

    public void prepareHistoricalMessages() {
        // 从 events.jsonl 同步加载历史事件，渲染为完成态卡片。
        // 统一管线：与实时流共用 processMessageEvent 状态机（fromHistory=true），
        // 消除历史加载的第二套合并语义导致的实时/历史显示不一致。
        List<AbstractEvent> events = sessionManager.getEvents(this.session.id());
        if (events.isEmpty()) {
            return;
        }

        historyLoading.set(true);
        try {
            // 召回事件持久化于同轮用户消息之前（召回 advisor 先于用户消息持久化执行），
            // 实时渲染时它出现在用户消息之后：此处缓存到下一条用户消息渲染完成再插入，两侧时序一致
            MemoryRecallEvent pendingRecall = null;
            for (AbstractEvent event : events) {
                if (event instanceof CompactionEvent ce) {
                    CompactionCard card = new CompactionCard(ce.getArchivedCount(), ce.getActiveCount());
                    messages.add(card);
                    if (currentState != null) currentState.savedMessages.add(card);
                    continue;
                }
                if (event instanceof MemoryRecallEvent mre) {
                    pendingRecall = mre;
                    continue;
                }
                if (!(event instanceof MessageEvent me)) {
                    continue;
                }
                // 压缩产生的影子轮次（shadow-prompt 用户消息 + 摘要助手消息）：框架伪消息，不渲染。
                // 压缩的提示由 CompactionEvent → CompactionCard 负责。
                // 其它 synthetic 事件（Goal 续轮 / 后台任务通知等系统注入）是真实发生的
                // 轮次边界：渲染为 NotificationCard，防止其两侧的 assistant 消息被错误合并
                // （实时流同样以 NotificationCard 渲染并隔断，两侧行为一致）。
                if (me.isSynthetic() && me.isCompactionShadow()) {
                    continue;
                }
                // TOOL 消息跳过：工具调用的历史重建由 assistant 消息的 toolCalls 字段
                // 在 processAssistantEvent 的历史分支完成
                if (me.isToolResponse()) {
                    continue;
                }
                boolean isUserMessage = me.isUserMessage();
                processMessageEvent(me, currentState, true, true);
                if (isUserMessage && pendingRecall != null) {
                    addMemoryRecallCard(pendingRecall.getFiles(), currentState, true);
                    pendingRecall = null;
                }
            }
            if (pendingRecall != null) {
                addMemoryRecallCard(pendingRecall.getFiles(), currentState, true);
            }
        } finally {
            historyLoading.set(false);
        }
    }

    public boolean hasHistoricalMessages() {
        return this.session != null
                && !sessionManager.getEvents(this.session.id()).isEmpty();
    }

    /** session 运行状态（供侧边栏列表显示，运行时状态不持久化） */
    public enum SessionStatus {
        /** 智能体执行中（含后台 session） */
        RUNNING,
        /** 用户已停止 */
        PAUSED,
        /** 空闲 */
        IDLE
    }

    /**
     * 查询指定 session 的运行状态（运行时状态，重启后均为 IDLE）。
     */
    public SessionStatus getSessionStatus(String sessionId) {
        SessionRuntimeState state = sessionStates.get(sessionId);
        if (state == null) {
            return SessionStatus.IDLE;
        }
        if (state.isPaused) {
            return SessionStatus.PAUSED;
        }
        return state.isStreaming ? SessionStatus.RUNNING : SessionStatus.IDLE;
    }

    // ===== Agent 构建 =====

    /**
     * 决定 memory 根目录。由子类按模式实现：work 用全局目录，code 用项目目录。
     */
    protected abstract Path resolveMemoryDir();

    /**
     * 生成 sessionId。由子类按模式实现：work 用 work 前缀，code 前缀并编码 projectName。
     */
    protected abstract String buildSessionId();

    /**
     * 构建 systemPrompt。由子类按模式实现：work 仅附环境块，code 注入项目规则并支持计划模式。
     */
    protected abstract String buildSystemPrompt(AgentDefinition definition);

    /**
     * 模式专有工具调整钩子：基类默认原样返回，不做任何裁剪。
     * code 子类在计划模式下覆盖为实现只读工具裁剪 + 追加 ExitPlanMode。
     */
    protected List<ToolCallback> applyPlanModeTools(List<ToolCallback> allTools) {
        return allTools;
    }

    /**
     * 模式专有 Hook 追加钩子：基类默认空实现。
     * code 子类覆盖以注册 GoalJudgeHook（目标闭环）、计划提交等。
     */
    protected void appendModeHooks(List<IAgentHook> hooks, ChatModel chatModel,
                                   cn.bitloom.agentic.memory.FileSystemAgentMemoryStore memoryStore) {
        // work 模式无 goal/plan 专有 hook
    }

    /**
     * session 创建时补充模式专有元数据：基类默认不写入。
     * code 子类覆盖以写入 projectId/projectName。
     */
    protected void applySessionMetadata(CreateSessionRequest.Builder builder) {
        // work 模式无项目元数据
    }

    /**
     * 构建 Agent。各调用方各自实现，不新建 AgentFactory。
     */
    protected Agent buildAgent(Session session, String agentId) {
        AgentDefinition definition = definitionManager.getOrLoadMainDefinition(agentId);
        ChatModel chatModel = modelFactory.model(Store.selectedModel.get());
        String uid = session.userId() != null ? session.userId() : "default-user";

        List<Advisor> advisors = new ArrayList<>();

        // 纯 token 压缩：DS 上下文 1M，达到 80%（800k token）时触发，压缩到约 60%（480k token）
        TokenCountCompactionStrategy tokenStrategy = TokenCountCompactionStrategy.builder()
                .maxTokens(480000)
                .build();
        SessionMemoryAdvisor sessionMemoryAdvisor = SessionMemoryAdvisor.builder(sessionManager)
                .defaultUserId(uid)
                .messageFilter(MessageFilter.byMessageType(MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL)
                        .and(MessageFilter.skipEmptyMessages()))
                .compactionTrigger(TokenCountTrigger.builder()
                        .threshold(800000)
                        .tokenCountEstimator(new JTokkitTokenCountEstimator())
                        .build())
                .compactionStrategy(tokenStrategy)
                .build();
        advisors.add(sessionMemoryAdvisor);

        Path memoriesDir = resolveMemoryDir();
        // 记忆自动化三件套共享同一 store：
        // (a) 选择式召回——仅首轮注入相关记忆背景；(b) 回合提取 Hook——见下方 hooks；
        // (c) 整理触发器——文件数 ≥ 阈值时注入 reminder 并由 Hook 异步整理
        FileSystemAgentMemoryStore memoryStore = new FileSystemAgentMemoryStore(memoriesDir);
        AgentMemoryAdvisor agentMemoryAdvisor = AgentMemoryAdvisor.builder()
                .memoryStore(memoryStore)
                .memoriesRootDirectory(memoriesDir.toString())
                .memoryConsolidationTrigger(
                        MemoryConsolidator.triggerWhen(memoryStore, MemoryConsolidator.DEFAULT_THRESHOLD))
                .build();
        advisors.add(agentMemoryAdvisor);

        advisors.add(AgentMemoryRecallAdvisor.builder()
                .sessionManager(sessionManager)
                .memoryStore(memoryStore)
                .chatClient(ChatClient.builder(chatModel).build())
                .build());

        advisors.add(SkillContextAdvisor.builder().skillManager(skillManager).build());

        advisors.add(SubagentContextAdvisor.builder()
                .definitionManager(definitionManager)
                .definition(definition)
                .build());
        advisors.add(EnvironmentContextAdvisor.builder().build());

        List<ToolCallback> allTools = new ArrayList<>(toolkit.buildToolCallbacks(definition));
        allTools.add(ConversationSearchTool.builder(sessionManager).build().toToolCallback());
        allTools.add(CrossSessionSearchTool.builder(sessionManager, uid).build().toToolCallback());

        // 模式专有工具调整（code 模式：计划模式裁剪为只读工具并追加 ExitPlanMode）
        allTools = applyPlanModeTools(allTools);

        // 基础 Hook 集：预算保护 / 权限审批 / Todo 提醒 / 工具结果落盘（每次 new，避免状态串扰）
        List<IAgentHook> hooks = new ArrayList<>();
        hooks.add(new ToolCallBudgetHook(configManager.getMaxToolCalls()));
        hooks.add(new PermissionHook(approvalStrategies));
        hooks.add(new TodoReminderHook());
        hooks.add(new ToolCardEventHook());
        hooks.add(new ToolResultOffloadHook());
        // 记忆自动化 (b)：回合结束异步提取长期记忆（仅主智能体，用户交互入口）
        hooks.add(MemoryExtractionHook.builder()
                .sessionManager(sessionManager)
                .memoryStore(memoryStore)
                .chatClient(ChatClient.builder(chatModel).build())
                .build());
        // 模式专有 Hook（code 模式：GoalJudgeHook 目标闭环）
        appendModeHooks(hooks, chatModel, memoryStore);

        Agent agent = Agent.builder()
                .name(agentId)
                .definition(definition)
                .model(chatModel)
                .systemPrompt(buildSystemPrompt(definition))
                .tools(allTools)
                .hooks(hooks)
                .advisors(advisors)
                // reactive_compact：上下文超长被 API 拒绝时强制压缩（绕过触发器）后重试一次
                .reactiveCompactor(sid -> sessionManager.compact(sid, req -> true, tokenStrategy))
                .build();
        log.info("构建智能体: agentId={}", agentId);
        return agent;
    }

    // ===== 发送消息 =====

    /**
     * 确保当前 session 存在（首次交互时创建）。sendMessage 与 slash 命令共用。
     */
    protected Session ensureSession() {
        if (this.session == null) {
            String agentId = Store.currentAgent.get();
            String sessionId = buildSessionId();
            CreateSessionRequest.Builder builder = CreateSessionRequest.builder()
                    .id(sessionId)
                    .userId(Store.userId.get());
            // 模式专有补充元数据（code 模式写入 projectId/projectName）
            applySessionMetadata(builder);
            this.session = sessionManager.create(builder.build());
            Store.currentSessionId.set(this.session.id());
        }
        return this.session;
    }

    /** evict 指定 session 的 Agent 缓存（下一次 sendMessage 按当前状态重建，如计划模式切换） */
    protected void evictAgent(String sessionId) {
        sessionAgents.remove(sessionId);
    }

    /**
     * 智能体提交计划（ExitPlanModeTool 回调，工具线程）。
     * 默认直接放弃；子类 override 实现批准 UI 与批准后自动执行。
     */
    protected void onPlanSubmitted(String sessionId, String plan, CompletableFuture<String> future) {
        future.complete(ExitPlanModeTool.DECISION_ABANDONED);
    }

    /** agent 流结束回调（doOnComplete，FX 线程）。子类可用于批准后的自动执行轮等 */
    protected void onStreamCompleted(String sessionId) {
    }

    public void sendMessage(String text) {
        ensureSession();

        // 确保 state 存在（首次发消息或切回后首次发消息都会创建）
        final String sid = this.session.id();
        boolean stateExisted = sessionStates.containsKey(sid);
        currentState = sessionStates.computeIfAbsent(sid, k -> new SessionRuntimeState());
        if (!stateExisted) {
            // state 是新建的，把当前 UI messages 同步进去（防止切回后丢失已有历史消息）
            currentState.savedMessages.addAll(messages);
        }

        // 兜底：清理上一轮 pause 后异步 after() 竞态写入的孤儿 toolCalls
        // 此时距上次 pause 已隔用户操作时间，in-flight after 必已完成，能可靠检测
        sessionManager.finalizeInterruptedToolCalls(sid);

        // per-session 流式状态：写 state，同步到 Store（active session 时驱动 UI）
        currentState.isStreaming = true;
        currentState.isPaused = false;
        Store.isStreaming.set(true);
        Store.isPaused.set(false);

        // 子类实现消息上下文构建（coder 模式附加项目信息）
        String messageText = buildMessageWithContext(text);
        MessageEvent inputEvent = MessageEvent.userMessage(sid, messageText);
        final Session currentSession = this.session;
        final String agentId = Store.currentAgent.get();
        final SessionRuntimeState stateRef = currentState;

        // 在后台线程执行，避免阻塞 FX 线程；per-session 锁保证串行
        CompletableFuture.runAsync(() -> {
            sessionManager.withLock(sid, () -> {
                try {
                    // Agent 与 session 1:1 绑定，session 级缓存（首次构建，后续复用）
                    Agent agent = sessionAgents.computeIfAbsent(sid,
                            k -> buildAgent(currentSession, agentId));
                    RuntimeContext ctx = RuntimeContext.builder()
                            .sessionId(sid)
                            .userId(currentSession.userId())
                            .projectPath(resolveProjectPath(currentSession))
                            .put("lastUserMessage", messageText)
                            .build();
                    subscribeAgentStream(agent, inputEvent, ctx, sid, stateRef);
                } catch (Exception e) {
                    log.error("sendMessage error", e);
                    Platform.runLater(() -> {
                        stateRef.isStreaming = false;
                        stateRef.isPaused = false;
                        if (currentState == stateRef) {
                            Store.isStreaming.set(false);
                            Store.isPaused.set(false);
                        }
                    });
                }
                return null;
            });
        });

        // 触发侧边栏刷新（更新会话标题）
        Store.refreshHistory.set(!Store.refreshHistory.get());
    }

    /**
     * 订阅 Agent 事件流（sendMessage 与 Goal Loop 自动续轮共用）。
     * pause 时直接 dispose 订阅：Agent.runStream 内部经 sink.onCancel 级联取消 LLM 流，
     * 走 cancel 路径而非 complete，因此不会误触发 onStreamCompleted 的续轮逻辑。
     */
    private void subscribeAgentStream(Agent agent, MessageEvent inputEvent, RuntimeContext ctx,
            String sid, SessionRuntimeState stateRef) {
        stateRef.subscription = agent.runStream(inputEvent, ctx)
                .doOnNext(event -> Platform.runLater(() -> processEvent(event, sid)))
                .doOnComplete(() -> Platform.runLater(() -> {
                    stateRef.isStreaming = false;
                    stateRef.isPaused = false;
                    // 仅当仍是 active session 时同步 Store，避免覆盖其他 session 的 UI 状态
                    boolean active = (currentState == stateRef);
                    if (active) {
                        Store.isStreaming.set(false);
                        Store.isPaused.set(false);
                    }
                    // 兜底收尾：流式输出偶发在思考/正文阶段被服务端或网络截断时，最后一个
                    // chunk 无 finishReason（不会走 STOP/TOOL_CALLS 收尾分支），思考/正文卡
                    // 残留悬挂。正常路径下收尾分支已置 null，此处为 no-op。
                    boolean truncated = stateRef.currentReasoningCard != null
                            || stateRef.currentAssistantCard != null;
                    if (stateRef.currentReasoningCard != null) {
                        stateRef.currentReasoningCard.finalizeSegment();
                        stateRef.currentReasoningCard = null;
                    }
                    collapseReasoningProcess(stateRef);
                    if (stateRef.currentToolGroup != null) {
                        stateRef.currentToolGroup.collapseNow();
                        stateRef.currentToolGroup = null;
                    }
                    stateRef.needNewToolGroup = true;
                    if (stateRef.currentAssistantCard != null) {
                        stateRef.currentAssistantCard.complete("STOP");
                        if (stateRef.currentAssistantCard.isEmpty()) {
                            if (active) messages.remove(stateRef.currentAssistantCard);
                            stateRef.savedMessages.remove(stateRef.currentAssistantCard);
                        }
                        stateRef.currentAssistantCard = null;
                    }
                    // 输出被截断（有思考/正文残留却无收尾）时补一条提示，避免用户误以为卡死
                    if (truncated) {
                        AssistantMessageCard tip = new AssistantMessageCard();
                        tip.appendContent("> 输出被中断了，请重新发送消息试试。");
                        tip.complete("STOP");
                        if (active) messages.add(tip);
                        stateRef.savedMessages.add(tip);
                    }
                    // 状态翻转，刷新侧边栏运行状态图标
                    Store.refreshHistory.set(!Store.refreshHistory.get());
                    onStreamCompleted(sid);
                }))
                .doOnError(e -> {
                    // pause 触发的取消异常不是真实错误，保留 paused 状态
                    if (stateRef.isPaused) {
                        return;
                    }
                    log.error("agent run error", e);
                    Platform.runLater(() -> {
                        stateRef.isStreaming = false;
                        stateRef.isPaused = false;
                        if (currentState == stateRef) {
                            Store.isStreaming.set(false);
                            Store.isPaused.set(false);
                        }
                        Store.refreshHistory.set(!Store.refreshHistory.get());
                    });
                })
                .subscribe();
    }

    /**
     * 自动续轮：以 synthetic 消息对 sessionId 发起下一次 runStream，无需用户输入。
     * Goal Loop（goal_feedback）与计划批准后的执行轮共用。
     */
    protected void continueRound(String sessionId, String message) {
        SessionRuntimeState stateRef = sessionStates.get(sessionId);
        if (stateRef == null) {
            return; // 非本 VM 管理的 session（coder/work 路由）
        }
        Session targetSession = sessionManager.getById(sessionId);
        if (targetSession == null) {
            return;
        }
        // 等待上一轮流式结束（judge 在 afterConversationRound 异步触发，与 doOnComplete 存在小概率竞态）
        long deadline = System.currentTimeMillis() + 10_000;
        while (stateRef.isStreaming && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (stateRef.isStreaming) {
            log.warn("目标续轮取消：上一轮仍在流式: session={}", sessionId);
            return;
        }
        // 用户已停止该 session：拒绝所有自动续轮（Goal 判定 / 后台任务恢复 / 计划批准执行轮），
        // 用户重新发消息时才会解除暂停
        if (stateRef.isPaused) {
            log.info("会话已停止，跳过自动续轮: session={}", sessionId);
            return;
        }

        stateRef.isStreaming = true;
        stateRef.isPaused = false;
        Platform.runLater(() -> {
            if (currentState == stateRef) {
                Store.isStreaming.set(true);
                Store.isPaused.set(false);
            }
            // 状态翻转，刷新侧边栏运行状态图标
            Store.refreshHistory.set(!Store.refreshHistory.get());
        });

        MessageEvent inputEvent = MessageEvent.userMessage(sessionId, message);
        inputEvent.setMetadata(Map.of(MessageEvent.METADATA_SYNTHETIC, Boolean.TRUE));

        CompletableFuture.runAsync(() -> {
            sessionManager.withLock(sessionId, () -> {
                try {
                    Agent agent = sessionAgents.get(sessionId);
                    if (agent == null) {
                        log.warn("目标续轮取消：Agent 缓存不存在: session={}", sessionId);
                        stateRef.isStreaming = false;
                        return null;
                    }
                    RuntimeContext ctx = RuntimeContext.builder()
                            .sessionId(sessionId)
                            .userId(targetSession.userId())
                            .projectPath(resolveProjectPath(targetSession))
                            .put("lastUserMessage", message)
                            .build();
                    subscribeAgentStream(agent, inputEvent, ctx, sessionId, stateRef);
                } catch (Exception e) {
                    log.error("continueGoalRound error", e);
                    stateRef.isStreaming = false;
                    Platform.runLater(() -> {
                        if (currentState == stateRef) {
                            Store.isStreaming.set(false);
                        }
                    });
                }
                return null;
            });
        });
    }

    /**
     * 解析 session 的 projectPath。work 模式无项目，返回 null。
     * code 子类按 session.metadata 的 projectId 解析（Goal 续轮时 session 可能非 active）。
     */
    protected String resolveProjectPath(Session session) {
        return null;
    }

    // ===== 事件处理 =====

    /**
     * 处理事件流中的事件（可能是 MessageEvent / CompactionEvent / UICardEvent）。
     * 按 sessionId 路由到对应 state：active session 同时更新 UI messages 与 savedMessages，
     * 非 active session 只更新 savedMessages（不污染 UI）。
     */
    private void processEvent(AbstractEvent event, String sessionId) {
        SessionRuntimeState state = sessionStates.get(sessionId);
        if (state == null) {
            log.warn("事件对应的 state 已被移除: sid={}, event={}", sessionId, event.getEventType());
            return;
        }
        boolean isActive = (state == currentState);

        if (event instanceof MessageEvent messageEvent) {
            processMessageEvent(messageEvent, state, isActive, false);
        } else if (event instanceof CompactionEvent ce) {
            CompactionCard card = new CompactionCard(ce.getArchivedCount(), ce.getActiveCount());
            if (isActive) messages.add(card);
            state.savedMessages.add(card);
        } else if (event instanceof UICardEvent uiCardEvent) {
            handleUICardEvent(uiCardEvent, state, isActive);
        } else if (event instanceof MemoryRecallEvent recallEvent) {
            addMemoryRecallCard(recallEvent.getFiles(), state, isActive);
        }
    }

    /**
     * 渲染记忆召回事件为「参考内容」折叠卡片（默认折叠，点击展开显示召回文件名，
     * 文件名为 Markdown 链接样式，点击打开对应记忆文件）。
     */
    private void addMemoryRecallCard(List<String> files, SessionRuntimeState state, boolean isActive) {
        if (files == null || files.isEmpty()) {
            return;
        }
        MemoryRecallCard card = new MemoryRecallCard(files, resolveMemoryDir().toFile());
        if (isActive) messages.add(card);
        state.savedMessages.add(card);
    }

    /**
     * 消费 UICardEvent：工具调用卡片（TOOL_CARD）由 ToolCardEventHook 发布。
     * <p>
     * CREATED 充当"助手流式结束"信号 — 结束当前流式文本卡片，并把该调用加入当前的
     * 工具调用组卡片（同一轮 AI 话语后的连续工具聚合为一组）；若无当前组或刚开启新话语，
     * 则新建一组卡片并以展开态显示执行中。
     * COMPLETED/FAILED 标记该调用完成，组内调用全部结束时折叠卡片。
     */
    private void handleUICardEvent(UICardEvent event, SessionRuntimeState state, boolean isActive) {
        if (event.getType() != UICardEvent.Type.TOOL_CARD) {
            return;
        }
        String callId = event.getCardId();
        String toolName = event.getToolName();
        switch (event.getStatus()) {
            case CREATED -> handleToolCallCreated(event, state, isActive, callId);
            case COMPLETED -> handleToolCallFinished(state, isActive, callId, toolName);
            case FAILED -> handleToolCallFinished(state, isActive, callId, toolName);
            default -> { }
        }
    }

    /** 工具调用开始：普通工具聚合到工具组卡片；Task/TodoWrite 独立渲染前闭合正文卡片。 */
    private void handleToolCallCreated(UICardEvent event, SessionRuntimeState state, boolean isActive, String callId) {
        // 任何工具执行前先闭合当前流式正文/思考，保证工具结束后的新 AI 文本新起一个冒泡
        finishStreamingText(state, isActive);
        if (TASK_TOOL_NAME.equals(event.getToolName()) || TODO_TOOL_NAME.equals(event.getToolName())) {
            // Task 由 ToolUIBridge 单独渲染 TaskCard、TodoWrite 渲染 TodoCard，不参与工具组
            return;
        }
        if (!isCardedTool(event.getToolName())) {
            // 非展示工具（MemoryView/Grep 等）：仅借 CREATED 事件分隔思考段，不创建工具组卡
            return;
        }
        if (callId != null) {
            state.activeToolCallIds.add(callId);
        }

        ToolCallCard group = state.currentToolGroup;
        if (group == null || state.needNewToolGroup) {
            // 新起一组（相邻同类合并：上一张卡也是工具组时复用该组）；新组开始默认展开，
            // 此后同组内新工具调用不再强制展开，尊重用户手动折叠
            group = obtainToolGroup(state, isActive);
            group.expandNow();
        }
        group.addToolCall(event.getToolName(), event.getCardJson());
        group.markRunning();
    }

    /** 工具调用结束：从组内活动集合移除，组内全部结束后折叠。 */
    private void handleToolCallFinished(SessionRuntimeState state, boolean isActive, String callId, String toolName) {
        if (TASK_TOOL_NAME.equals(toolName) || TODO_TOOL_NAME.equals(toolName)) {
            return; // Task / TodoWrite 不参与 ToolCallCard 组管理
        }
        if (!isCardedTool(toolName)) {
            return; // 非展示工具不参与工具组管理
        }
        if (callId != null) {
            state.activeToolCallIds.remove(callId);
        }
        ToolCallCard group = state.currentToolGroup;
        if (group != null && state.activeToolCallIds.isEmpty()) {
            group.collapseNow();
        }
    }

    /** 展示工具组卡片的工具。其余工具（MemoryView/Grep 等）仅用于分隔思考段，不展示工具卡。 */
    private static boolean isCardedTool(String toolName) {
        return switch (toolName) {
            case "Read", "Write", "Edit", "Command" -> true;
            default -> false;
        };
    }

    // ===== 相邻同类卡片合并（实时流式 / 历史加载共用） =====
    // 新建卡片前检查列表最后一张卡：同类型则合并进去（思考分段追加 / 正文重开续写 / 工具组复用），
    // 避免思考模式下三类卡片交错产生碎片化列表项。被其他类型卡片隔开的同类卡保持独立按时序展示。

    /** 取卡片列表最后一张（savedMessages 优先，无 state 时回退 UI messages）。 */
    private MessageCard peekLastCard(SessionRuntimeState state) {
        List<MessageCard> source = state != null ? state.savedMessages : messages;
        if (source.isEmpty()) {
            return null;
        }
        return source.get(source.size() - 1);
    }

    /** 合并感知的卡片追加：active 时同时进 UI messages 与 savedMessages。 */
    private void addCardToLists(MessageCard card, SessionRuntimeState state, boolean isActive) {
        if (isActive) {
            messages.add(card);
        }
        if (state != null) {
            state.savedMessages.add(card);
        }
    }

    /**
     * 获取「思考过程」折叠容器：已存在（未闭合）则复用；
     * 上一张列表项已是「思考过程」容器（前一段思考在无正文轮次结束时折叠闭合）则复用重开，
     * 使连续（相邻、无正文分隔）的思考过程合并进同一折叠块；
     * 否则新建列表项并展开。
     * 思考段与思考引发的工具组都收进该容器，正文开始或轮次结束时折叠闭合。
     */
    private ReasoningProcessCard obtainReasoningProcess(SessionRuntimeState state, boolean isActive) {
        if (state.currentReasoningProcess != null) {
            return state.currentReasoningProcess;
        }
        if (peekLastCard(state) instanceof ReasoningProcessCard existing) {
            existing.expand();
            state.currentReasoningProcess = existing;
            return existing;
        }
        ReasoningProcessCard card = new ReasoningProcessCard();
        addCardToLists(card, state, isActive);
        card.expand();
        state.currentReasoningProcess = card;
        return card;
    }

    /** 折叠并闭合当前「思考过程」容器（正文开始 / 轮次结束时调用）。 */
    private void collapseReasoningProcess(SessionRuntimeState state) {
        if (state.currentReasoningProcess != null) {
            state.currentReasoningProcess.collapse();
            state.currentReasoningProcess = null;
        }
    }

    /**
     * 获取思考子块：归入「思考」二级节点（容器尾部二级节点是思考则复用，否则新建），
     * 节点内尾块是思考子块 → 复用并分段追加（beginNewSegment）；否则新建子块。
     */
    private ReasoningCard obtainReasoningCard(SessionRuntimeState state, boolean isActive) {
        ReasoningProcessCard container = obtainReasoningProcess(state, isActive);
        ProcessSectionNode section = container.thinkingSection();
        // 思考段流式输出中默认展开，定格（finalizeSegment）时自动折叠
        section.expand();
        if (section.lastContent() instanceof ReasoningCard existing) {
            existing.beginNewSegment();
            return existing;
        }
        ReasoningCard card = new ReasoningCard();
        section.addContent(card);
        return card;
    }

    /** 获取正文卡：最后一张是 AssistantMessageCard → 复用并重开续写（reopen）；否则新建。 */
    private AssistantMessageCard obtainAssistantCard(SessionRuntimeState state, boolean isActive) {
        if (peekLastCard(state) instanceof AssistantMessageCard existing) {
            existing.reopen();
            return existing;
        }
        AssistantMessageCard card = new AssistantMessageCard();
        addCardToLists(card, state, isActive);
        return card;
    }

    /**
     * 获取工具组卡。思考容器存在（未闭合）→ 工具组用平铺模式直接挂进容器 body
     * （无折叠标题行、无「工具调用」二级节点标题），思考引发的工具明细直接展示，
     * 避免「思考过程折叠 > 工具折叠」嵌套；容器已闭合/不存在（正文后的纯工具调用）
     * → 折叠模式的独立列表项（与思考过程折叠同级），相邻工具组复用。
     */
    private ToolCallCard obtainToolGroup(SessionRuntimeState state, boolean isActive) {
        if (state != null && state.currentReasoningProcess != null) {
            ReasoningProcessCard container = state.currentReasoningProcess;
            if (!state.needNewToolGroup) {
                ToolCallCard last = container.lastToolCard();
                if (last != null) {
                    state.currentToolGroup = last;
                    return last;
                }
            }
            ToolCallCard group = new ToolCallCard(resolveProjectPath(session), true);
            state.currentToolGroup = group;
            state.needNewToolGroup = false;
            container.addToolCard(group);
            return group;
        }
        if (state != null && peekLastCard(state) instanceof NodeMessageCard nmc
                && nmc.getNode() instanceof ToolCallCard existing) {
            state.currentToolGroup = existing;
            state.needNewToolGroup = false;
            return existing;
        }
        ToolCallCard group = new ToolCallCard(resolveProjectPath(session));
        NodeMessageCard wrapper = new NodeMessageCard(group);
        if (state != null) {
            state.currentToolGroup = group;
            state.needNewToolGroup = false;
        }
        addCardToLists(wrapper, state, isActive);
        return group;
    }

    /** 卡片内容高度变化时触发（供子类桥接外部滚动刷新，默认空实现）。 */
    protected void onCardContentChanged() {
    }

    /** 结束当前流式 assistant 文本卡片（未结束则移除空卡）。 */
    private void finishStreamingText(SessionRuntimeState state, boolean isActive) {
        // 工具分界：思考段一并定格（必须先于空卡提前返回，否则仅思考无正文的轮次
        // currentReasoningCard 残留，下一段思考会覆盖替换本段思考内容）。
        // 容器保持开启：随后的工具组收进同一「思考过程」折叠层
        if (state.currentReasoningCard != null) {
            state.currentReasoningCard.finalizeSegment();
            state.currentReasoningCard = null;
        }
        if (state.currentAssistantCard == null) {
            return;
        }
        AssistantMessageCard card = state.currentAssistantCard;
        state.currentAssistantCard = null;
        card.complete("TOOL_CALLS");
        if (card.isEmpty()) {
            if (isActive) messages.remove(card);
            state.savedMessages.remove(card);
        }
    }

    private void processMessageEvent(MessageEvent event, SessionRuntimeState state, boolean isActive,
            boolean fromHistory) {
        if (event.isUserMessage()) {
            processUserEvent(event, state, isActive);
        } else if (event.isAssistantMessage()) {
            processAssistantEvent(event, state, isActive, fromHistory);
        } else {
            log.warn("未处理的事件类型: {}", event.getEventType());
        }
    }

    private void processUserEvent(MessageEvent e, SessionRuntimeState state, boolean isActive) {
        state.currentAssistantCard = null;
        // 新一轮用户交互：重置思考容器/思考段与工具组，后续事件新起模块
        collapseReasoningProcess(state);
        state.currentReasoningCard = null;
        state.currentToolGroup = null;
        state.needNewToolGroup = true;
        state.activeToolCallIds.clear();
        // synthetic 消息（Goal 续轮 goal_feedback / 后台任务通知等系统注入）以通知样式渲染
        MessageCard card = e.isSynthetic()
                ? new cn.bitloom.node.message.NotificationCard(e.getText())
                : new UserMessageCard(e.getText());
        if (isActive) messages.add(card);
        state.savedMessages.add(card);
    }

    /**
     * assistant 消息渲染状态机：实时流（chunk 累积）与历史加载（聚合完整消息）
     * 共用，保证合并语义一致。
     * <p>
     * 实时流：finishReason 为空的 chunk 逐条累积（text 追加、reasoning 覆盖），
     * TOOL_CALLS / STOP 收尾；历史加载（fromHistory=true）：每条消息即聚合完整
     * 内容（reasoning/text/toolCalls 齐备），不存在 chunk 形态，finishReason
     * 缺失时兜底按收尾处理。
     */
    private void processAssistantEvent(MessageEvent e, SessionRuntimeState state, boolean isActive,
            boolean fromHistory) {
        String finishReason = e.getFinishReason();
        String text = e.getText();
        String reasoning = e.getReasoningContent();
        // 历史事件为聚合完整消息，无流式 chunk 形态
        boolean isChunk = !fromHistory
                && (finishReason == null || finishReason.isBlank() || "_UNKNOWN".equals(finishReason));

        if (isChunk) {
            // 流式 chunk：直接累积。per-session isPaused 控制是否累积
            if (state.isPaused) {
                return;
            }
            // 思考流：每段连续思考一个 ReasoningCard 子块，分段互不覆盖；
            // 思考 chunk 先于本轮正文/工具产生，追加即为其时序位置
            // （思考+工具链 → 正文 → 下一个思考+工具链 …）
            // 正文流开启后到达的 reasoning 忽略：部分 API 正文 chunk 会携带完整
            // reasoningContent（残留），若不忽略会重复渲染出相同内容的思考
            if (reasoning != null && !reasoning.isBlank() && state.currentAssistantCard == null) {
                if (state.currentReasoningCard == null) {
                    // 新思考段开始：容器内尾块也是思考（连续思考）则分段追加，否则新建子块
                    state.currentReasoningCard = obtainReasoningCard(state, isActive);
                }
                state.currentReasoningCard.updateReasoning(reasoning);
            }
            // 正文流：独立卡片累积（chunk 级 reasoning/content 双发不会互相打断）
            if (state.currentAssistantCard == null) {
                // 空文本的 chunk（如工具间 silent revision：无文本、仅继续调用工具）不构成
                // 新的 AI 话语，不新建卡片。
                if ((text == null || text.isBlank())) {
                    return;
                }
                // 相邻同类合并：上一张卡也是正文卡时重开续写，否则新建
                state.currentAssistantCard = obtainAssistantCard(state, isActive);
                // 正文开始：当前思考段定格，「思考过程」容器整体折叠闭合，
                // 此后（跨工具轮次）的新思考新起一个容器
                if (state.currentReasoningCard != null) {
                    state.currentReasoningCard.finalizeSegment();
                    state.currentReasoningCard = null;
                }
                collapseReasoningProcess(state);
                // 新一条 AI 话语开始（出现实质文本）：此后的工具调用归属于新的一组
                state.needNewToolGroup = true;
            }
            if (text != null && !text.isBlank()) {
                state.currentAssistantCard.appendContent(text);
            }
        } else if ("TOOL_CALLS".equals(finishReason)) {
            if (fromHistory) {
                // 历史聚合形态：一条消息同时携带完整 reasoning/text/toolCalls，
                // 与实时流（chunk 累积 → TOOL_CALLS 收尾）等价地依次定格渲染
                renderHistoryReasoning(reasoning, state, isActive);
                if (text != null && !text.isBlank()) {
                    // 正文出现：思考段定格、容器折叠（与实时正文开始路径一致），
                    // 随后的工具组走独立列表项分支
                    if (state.currentReasoningCard != null) {
                        state.currentReasoningCard.finalizeSegment();
                        state.currentReasoningCard = null;
                    }
                    collapseReasoningProcess(state);
                    state.needNewToolGroup = true;
                    AssistantMessageCard card = obtainAssistantCard(state, isActive);
                    card.appendContent(text);
                    card.complete("TOOL_CALLS");
                }
                if (e.hasToolCalls()) {
                    // 实时由 UICardEvent 驱动工具组，历史由 toolCalls 字段重建为已完成
                    // 折叠态的工具组卡片（相邻工具组合并）。仅重建展示工具，与实时一致
                    // （避免空命令卡、并保持思考容器边界一致）。
                    // 思考过程未闭合（无正文）→ 思考过程中的工具调用不显示，跳过重建
                    List<MessageEvent.ToolCallInfo> cardedCalls = e.getToolCalls().stream()
                            .filter(tc -> isCardedTool(tc.name()))
                            .toList();
                    if (!cardedCalls.isEmpty()) {
                        ToolCallCard group = obtainToolGroup(state, isActive);
                        for (MessageEvent.ToolCallInfo tc : cardedCalls) {
                            group.addToolCall(tc.name(), tc.arguments());
                        }
                        group.collapseNow();
                    }
                }
            } else {
                // 工具调用即将发生：闭合当前正文卡片；思考容器保持开启，
                // 随后的工具组收进同一「思考过程」折叠层，工具结束后的新 AI 文本
                // 自然新起一个正文卡片 —— 严格按事件顺序展示。
                finishStreamingText(state, isActive);
            }
        } else {
            // 收尾：STOP / LENGTH 等结束原因（含历史 finishReason 缺失的兜底）。
            // 原实现仅匹配 STOP，其它 finishReason 的最终文本会被静默丢弃
            state.isStreaming = false;
            state.isPaused = false;
            // 流结束收尾：思考段定格，「思考过程」容器与工具组自动折叠
            if (state.currentReasoningCard != null) {
                state.currentReasoningCard.finalizeSegment();
                state.currentReasoningCard = null;
            }
            collapseReasoningProcess(state);
            if (state.currentToolGroup != null) {
                state.currentToolGroup.collapseNow();
            }
            // 收尾重置工具组状态：自动续轮（Goal Loop / 计划执行轮，不经过 processUserEvent）
            // 若残留旧引用会跨轮复用上一轮的工具组卡片
            state.currentToolGroup = null;
            state.needNewToolGroup = true;
            if (isActive) {
                Store.isStreaming.set(false);
                Store.isPaused.set(false);
            }

            // 历史/聚合消息携带的思考内容：渲染定格思考子块。实时收尾 chunk 的
            // reasoningContent 是正文流残留，不处理（思考已由 chunk 分支渲染）
            if (fromHistory) {
                renderHistoryReasoning(reasoning, state, isActive);
                // 历史收尾：思考段与容器定格折叠（无后续事件会再折叠它们）
                if (state.currentReasoningCard != null) {
                    state.currentReasoningCard.finalizeSegment();
                    state.currentReasoningCard = null;
                }
                collapseReasoningProcess(state);
            }

            if (state.currentAssistantCard != null) {
                state.currentAssistantCard.complete("STOP");
                if (state.currentAssistantCard.isEmpty()) {
                    if (isActive) messages.remove(state.currentAssistantCard);
                    state.savedMessages.remove(state.currentAssistantCard);
                }
                state.currentAssistantCard = null;
            } else if (text != null && !text.isBlank()) {
                // 非流式消息（历史消息或一次性输出）：相邻正文卡合并重开续写
                AssistantMessageCard card = obtainAssistantCard(state, isActive);
                card.appendContent(text);
                card.complete("STOP");
            }
        }
    }

    /**
     * 历史消息的思考内容渲染：定格渲染为思考子块。
     * 连续思考（容器内尾块是思考）由 obtainReasoningCard 合并分段追加。
     */
    private void renderHistoryReasoning(String reasoning, SessionRuntimeState state, boolean isActive) {
        if (reasoning == null || reasoning.isBlank()) {
            return;
        }
        ReasoningCard card = obtainReasoningCard(state, isActive);
        card.updateReasoning(reasoning);
        card.finalizeSegment();
    }

    public void addUserMessage(String text) {
        UserMessageCard card = new UserMessageCard(text);
        messages.add(card);
        if (currentState != null) {
            currentState.savedMessages.add(card);
        }
    }

    /**
     * 向 UI messages 添加节点消息卡片（如 TaskCard），按所属 sessionId 路由：
     * active session 同时更新 UI messages 与 savedMessages；
     * 非 active session 只更新对应 state 的 savedMessages（避免污染当前 UI，切回时恢复显示）。
     * 由 Controller 通过 toolUIBridge 回调调用。
     */
    public void addNodeMessage(String sessionId, javafx.scene.Node node) {
        SessionRuntimeState state = sessionStates.get(sessionId);
        if (state == null) {
            // 兜底：找不到所属 state 时回落到当前 active state（正常情况下任务卡片所属 session 必在 state 中）
            state = currentState;
        }
        NodeMessageCard card = new NodeMessageCard(node);
        if (state == null) {
            messages.add(card);
            return;
        }
        boolean isActive = (state == currentState);
        if (isActive) {
            messages.add(card);
        }
        state.savedMessages.add(card);
    }

    public void clear() {
        // 清空当前 session 的 UI 与 state
        messages.clear();
        if (currentState != null) {
            currentState.savedMessages.clear();
            currentState.currentAssistantCard = null;
        }
        Store.isStreaming.set(false);
        Store.isPaused.set(false);
        if (this.session != null) {
            // 取消该 session 的订阅并移除缓存（session 整体销毁）
            SessionRuntimeState state = sessionStates.remove(this.session.id());
            if (state != null) cancelStateSubscription(state);
            sessionAgents.remove(this.session.id());
            currentState = null;
            sessionManager.remove(this.session.id());
            sessionManager.persistSession(this.session);
        }
    }

    public void pauseGeneration() {
        // 仅暂停当前 active session（用户点 stop 按钮）
        if (currentState == null) return;
        if (!currentState.isStreaming || currentState.isPaused) return;

        currentState.isStreaming = false;
        currentState.isPaused = true;
        Store.isStreaming.set(false);
        Store.isPaused.set(true);
        cancelStateSubscription(currentState);
        // 状态翻转，刷新侧边栏运行状态图标
        Store.refreshHistory.set(!Store.refreshHistory.get());

        // 中途停止时善后事件文件，避免下次调用 LLM 时历史不成对导致报错：
        //   1. 保存已流式生成的 assistant 文本为 STOP 事件，避免上一轮内容丢失
        //   2. 为末尾不成对的 assistant(toolCalls) 补虚拟 ToolResponse（若存在）
        //      — pause 时调用是尽力而为；竞态残留的孤儿由 sendMessage 开头兜底再补
        if (this.session != null) {
            String sid = this.session.id();

            // 中途停止时已生成的思考一并落盘（历史重建才不会丢失最后一段思考）：
            // 取「思考过程」容器内全部思考子块内容（跨工具轮次的多段思考都在其中）
            if (currentState.currentReasoningCard != null) {
                currentState.currentReasoningCard.finalizeSegment();
                currentState.currentReasoningCard = null;
            }
            ReasoningProcessCard processCard = currentState.currentReasoningProcess;
            String reasoningText = processCard != null ? processCard.getContent() : null;
            if (processCard != null) {
                processCard.collapse();
                currentState.currentReasoningProcess = null;
            }

            if (currentState.currentAssistantCard != null) {
                AssistantMessageCard card = currentState.currentAssistantCard;
                // 定格：accumulator → content + Markdown 渲染。不能用 setStreaming(false)，
                // 流式期间 content 为空，会触发 renderMarkdown(空) 把已流出正文从 UI 清空
                card.complete("STOP");
                String partial = card.getContent();
                if (card.isEmpty()) {
                    // 空卡移除（避免残留空白列表项）
                    messages.remove(card);
                    currentState.savedMessages.remove(card);
                } else if (reasoningText != null && !reasoningText.isBlank()) {
                    sessionManager.appendEvent(MessageEvent.assistantStop(sid, partial, reasoningText));
                } else {
                    sessionManager.appendEvent(MessageEvent.assistantStop(sid, partial));
                }
                currentState.currentAssistantCard = null;
            } else if (reasoningText != null && !reasoningText.isBlank()) {
                // 仅思考无正文（停止时正在思考）：落盘带 reasoning 的 STOP 事件
                sessionManager.appendEvent(MessageEvent.assistantStop(sid, "", reasoningText));
            }

            sessionManager.finalizeInterruptedToolCalls(sid);
        } else if (currentState.currentAssistantCard != null) {
            // session 尚未落盘（新建会话）：仅定格 UI，同样不能用 setStreaming(false) 清空卡片
            currentState.currentAssistantCard.complete("STOP");
            if (currentState.currentAssistantCard.isEmpty()) {
                messages.remove(currentState.currentAssistantCard);
            }
            currentState.currentAssistantCard = null;
        }
    }

    /**
     * 取消指定 state 的订阅（不删除 state 本身，便于切回恢复）。
     * dispose 经 Agent.runStream 的 sink.onCancel 级联取消内部 LLM 流与工具循环。
     */
    private void cancelStateSubscription(SessionRuntimeState state) {
        if (state.subscription != null && !state.subscription.isDisposed()) {
            state.subscription.dispose();
        }
        state.subscription = null;
    }

    /**
     * 释放资源（模式切换时调用）：取消所有 session 的订阅、清空所有缓存。
     * 子类可 override 扩展清理逻辑，但必须 super.dispose()。
     */
    public void dispose() {
        for (SessionRuntimeState state : sessionStates.values()) {
            cancelStateSubscription(state);
        }
        sessionStates.clear();
        sessionAgents.clear();
        currentState = null;
    }

    // ===== 抽象方法：子类实现模式专有逻辑 =====

    /**
     * 构建带上下文的消息文本。
     * coder 模式附加项目信息前缀，work 模式直接返回原文。
     */
    protected abstract String buildMessageWithContext(String text);

    /**
     * 模式切换时的专有逻辑。
     * coder 模式：非 coder 时清空 currentProject。
     * work 模式：空实现。
     */
    protected abstract void onSwitchAgent(String agentId);

    /**
     * 会话切换后的专有逻辑。
     * coder 模式：从 session.metadata 恢复 currentProject。
     * work 模式：默认空实现。
     */
    protected void onSessionSwitched(Session session) {
        // 默认空实现，子类可重写
    }
}
