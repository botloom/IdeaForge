package cn.bitloom.bridge.wechat;

import cn.bitloom.agentic.agent.Agent;
import cn.bitloom.agentic.agent.AgentDefinition;
import cn.bitloom.agentic.agent.AgentDefinitionManager;
import cn.bitloom.agentic.agent.interceptor.AgentMemoryInterceptor;
import cn.bitloom.agentic.agent.interceptor.SessionMemoryInterceptor;
import cn.bitloom.agentic.event.MessageEvent;
import cn.bitloom.agentic.model.ModelFactory;
import cn.bitloom.agentic.session.CreateSessionRequest;
import cn.bitloom.agentic.session.FileSystemSessionManager;
import cn.bitloom.agentic.session.MessageFilter;
import cn.bitloom.agentic.session.Session;
import cn.bitloom.agentic.session.SessionTypeEnum;
import cn.bitloom.agentic.session.compaction.RecursiveSummarizationCompactionStrategy;
import cn.bitloom.agentic.session.compaction.TokenCountTrigger;
import cn.bitloom.agentic.tool.Toolkit;
import cn.bitloom.agentic.tool.session.ConversationSearchTool;
import cn.bitloom.agentic.tool.session.CrossSessionSearchTool;
import cn.bitloom.bridge.wechat.ilink.model.MessageItem;
import cn.bitloom.bridge.wechat.ilink.model.WeixinMessage;
import cn.bitloom.constant.AppConstants;
import cn.bitloom.harness.llm.ChatModel;
import cn.bitloom.harness.llm.Role;
import cn.bitloom.harness.llm.TokenCountEstimator;
import cn.bitloom.harness.loop.LoopContext;
import cn.bitloom.harness.loop.LoopInterceptor;
import cn.bitloom.harness.tool.ToolCallback;
import cn.bitloom.store.Store;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Slf4j
public class WechatILinkMessageHandler {

    private static final String SOURCE = "wechat";
    private static final String DEFAULT_AGENT_ID = "work";
    private final FileSystemSessionManager fileSystemSessionManager;
    private final Supplier<WechatILinkClient> wechatILinkClientProvider;
    private final AgentDefinitionManager definitionManager;
    private final ModelFactory modelFactory;
    private final Toolkit toolkit;
    private final Map<String, Session> sessionMap = new ConcurrentHashMap<>();

    public WechatILinkMessageHandler(FileSystemSessionManager fileSystemSessionManager,
                                     Supplier<WechatILinkClient> wechatILinkClientProvider,
                                     AgentDefinitionManager definitionManager,
                                     ModelFactory modelFactory,
                                     Toolkit toolkit) {
        this.fileSystemSessionManager = fileSystemSessionManager;
        this.wechatILinkClientProvider = wechatILinkClientProvider;
        this.definitionManager = definitionManager;
        this.modelFactory = modelFactory;
        this.toolkit = toolkit;
    }

    /**
     * 处理微信消息：获取/创建 session，直接调用 Agent.runStream，回复通过 wechatILinkClient 发送。
     */
    public void handleMessage(WeixinMessage message) {
        String userId = message.getFromUserId();
        String text = extractText(message);
        if (text == null || text.isBlank()) {
            return;
        }

        Session session = sessionMap.computeIfAbsent(userId, this::bindSession);
        MessageEvent inputEvent = MessageEvent.userMessage(session.id(), text.trim());

        fileSystemSessionManager.withLock(session.id(), () -> {
            try {
                Agent agent = buildAgent(session, DEFAULT_AGENT_ID);
                LoopContext ctx = LoopContext.builder()
                        .sessionId(session.id())
                        .userId(userId)
                        .build();
                agent.runStream(inputEvent, ctx)
                        .doOnNext(event -> {
                            if (event instanceof MessageEvent me
                                    && me.isAssistantMessage()
                                    && me.getText() != null
                                    && !me.getText().isBlank()) {
                                wechatILinkClientProvider.get().sendText(userId, me.getText().trim());
                            }
                        })
                        .doOnError(e -> log.error("Wechat agent run error: userId={}", userId, e))
                        .blockLast();
            } catch (Exception e) {
                log.error("Wechat handleMessage error: userId={}", userId, e);
            }
            return null;
        });
    }

    /**
     * 为微信用户绑定一个 session
     */
    private Session bindSession(String userId) {
        String sessionId = "work-" + SessionTypeEnum.DM + "-" + SOURCE + "-" + userId + "-" + System.currentTimeMillis();
        CreateSessionRequest request = CreateSessionRequest.builder()
                .id(sessionId)
                .userId(userId)
                .build();
        Session session = fileSystemSessionManager.create(request);
        log.info("[Wechat] 绑定 session: userId={}, sessionId={}", userId, sessionId);
        return session;
    }

    /**
     * 构建 Agent（各调用方各自实现，不新建 AgentFactory）。
     */
    private Agent buildAgent(Session session, String agentId) {
        AgentDefinition definition = definitionManager.getOrLoadMainDefinition(agentId);
        ChatModel chatModel = modelFactory.model(Store.selectedModel.get());
        String uid = session.userId() != null ? session.userId() : "default-user";

        List<LoopInterceptor> interceptors = new ArrayList<>();

        SessionMemoryInterceptor sessionMemoryInterceptor = SessionMemoryInterceptor.builder(fileSystemSessionManager)
                .defaultUserId(uid)
                .messageFilter(MessageFilter.byMessageType(Role.USER, Role.ASSISTANT, Role.TOOL)
                        .and(MessageFilter.skipEmptyMessages()))
                .compactionTrigger(TokenCountTrigger.builder()
                        .threshold(100000)
                        .tokenCountEstimator(new TokenCountEstimator())
                        .build())
                .compactionStrategy(RecursiveSummarizationCompactionStrategy.builder(chatModel)
                        .build())
                .build();
        interceptors.add(sessionMemoryInterceptor);

        Path memoriesDir = AppConstants.Memory.workMemoryDir();
        AgentMemoryInterceptor agentMemoryInterceptor = AgentMemoryInterceptor.builder()
                .memoriesRootDirectory(memoriesDir.toString())
                .build();
        interceptors.add(agentMemoryInterceptor);

        List<ToolCallback> allTools = new ArrayList<>(toolkit.buildToolCallbacks(definition));
        allTools.add(ConversationSearchTool.builder(fileSystemSessionManager).build().toToolCallback());
        allTools.add(CrossSessionSearchTool.builder(fileSystemSessionManager, uid).build().toToolCallback());

        Agent agent = Agent.builder()
                .name(agentId)
                .definition(definition)
                .model(chatModel)
                .systemPrompt(definition.content())
                .tools(allTools)
                .interceptors(interceptors)
                .build();
        log.info("构建微信智能体: agentId={}", agentId);
        return agent;
    }

    private String extractText(WeixinMessage message) {
        if (message.getItemList() == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (MessageItem item : message.getItemList()) {
            if (item.getTextItem() != null && item.getTextItem().getText() != null) {
                sb.append(item.getTextItem().getText());
            }
        }
        return sb.toString();
    }
}
