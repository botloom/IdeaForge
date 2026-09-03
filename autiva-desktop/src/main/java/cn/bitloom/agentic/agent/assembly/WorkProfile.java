package cn.bitloom.agentic.agent.assembly;

import cn.bitloom.agentic.agent.interceptor.AgentMemoryInterceptor;
import cn.bitloom.agentic.agent.interceptor.AgentMemoryRecallInterceptor;
import cn.bitloom.agentic.agent.interceptor.EnvironmentContextInterceptor;
import cn.bitloom.agentic.agent.interceptor.FileChangeRecorderInterceptor;
import cn.bitloom.agentic.agent.interceptor.MemoryExtractionInterceptor;
import cn.bitloom.agentic.agent.interceptor.PermissionInterceptor;
import cn.bitloom.agentic.agent.interceptor.SelfModifyWatchInterceptor;
import cn.bitloom.agentic.agent.interceptor.SessionMemoryInterceptor;
import cn.bitloom.agentic.agent.interceptor.SkillContextInterceptor;
import cn.bitloom.agentic.agent.interceptor.SubagentContextInterceptor;
import cn.bitloom.agentic.agent.interceptor.TodoReminderInterceptor;
import cn.bitloom.agentic.agent.interceptor.ToolCallBudgetInterceptor;
import cn.bitloom.agentic.agent.interceptor.ToolCardEventInterceptor;
import cn.bitloom.agentic.agent.interceptor.ToolResultOffloadInterceptor;
import cn.bitloom.agentic.memory.MemoryConsolidator;
import cn.bitloom.agentic.session.MessageFilter;
import cn.bitloom.agentic.session.compaction.TokenCountTrigger;
import cn.bitloom.agentic.tool.plugin.PluginListTool;
import cn.bitloom.agentic.tool.plugin.PluginMountTool;
import cn.bitloom.agentic.tool.plugin.PluginUnmountTool;
import cn.bitloom.agentic.tool.session.ConversationSearchTool;
import cn.bitloom.agentic.tool.session.CrossSessionSearchTool;
import cn.bitloom.agentic.tool.util.CurrentTimeTool;
import cn.bitloom.harness.kernel.Plugin;
import cn.bitloom.harness.llm.Role;
import cn.bitloom.harness.llm.TokenCountEstimator;
import cn.bitloom.harness.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * work 模式 Profile — 主智能体的完整插件集（会话记忆 / 记忆三件套 / 技能与环境上下文 /
 * 子代理上下文 / 基础横切拦截器 / 工具集）。
 * <p>
 * code 模式（{@link CodeProfile}）在本插件集之上追加目标闭环与计划模式工具裁剪。
 */
public class WorkProfile implements AgentProfile {

    protected final AgentAssemblyContext ctx;

    public WorkProfile(AgentAssemblyContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String name() {
        return "work";
    }

    @Override
    public List<Plugin> plugins() {
        return List.of(
                sessionMemoryPlugin(),
                agentMemoryPlugin(),
                skillContextPlugin(),
                subagentContextPlugin(),
                environmentContextPlugin(),
                baseInterceptorsPlugin(),
                toolsPlugin(),
                timeToolPlugin());
    }

    // ===== 工具集：时钟（演示装配层插件贡献真实新工具） =====

    /** 时钟插件：向工具注册表注入 CurrentTime 工具（code/work 主智能体均可见）。 */
    protected Plugin timeToolPlugin() {
        return new SimplePlugin("clock-tool", c ->
                c.inject(AgentServiceKeys.TOOLS).add(new CurrentTimeTool().toToolCallback()));
    }

    // ===== 会话记忆 =====

    protected Plugin sessionMemoryPlugin() {
        return new SimplePlugin("session-memory", c ->
                c.inject(AgentServiceKeys.INTERCEPTORS).add(SessionMemoryInterceptor.builder(ctx.getSessionManager())
                        .defaultUserId(ctx.getUid())
                        .messageFilter(MessageFilter.byMessageType(Role.USER, Role.ASSISTANT, Role.TOOL)
                                .and(MessageFilter.skipEmptyMessages()))
                        .compactionTrigger(TokenCountTrigger.builder()
                                .threshold(800000)
                                .tokenCountEstimator(new TokenCountEstimator())
                                .build())
                        .compactionStrategy(ctx.getCompactionStrategy())
                        .build()));
    }

    // ===== 记忆三件套（召回 + 整理触发器 + 回合提取） =====

    protected Plugin agentMemoryPlugin() {
        return new SimplePlugin("agent-memory", c -> {
            c.inject(AgentServiceKeys.INTERCEPTORS).add(AgentMemoryInterceptor.builder()
                    .memoryStore(ctx.getMemoryStore())
                    .memoriesRootDirectory(ctx.getMemoriesDir().toString())
                    .memoryConsolidationTrigger(MemoryConsolidator.triggerWhen(
                            ctx.getMemoryStore(), MemoryConsolidator.DEFAULT_THRESHOLD))
                    .build());
            c.inject(AgentServiceKeys.INTERCEPTORS).add(AgentMemoryRecallInterceptor.builder()
                    .sessionManager(ctx.getSessionManager())
                    .memoryStore(ctx.getMemoryStore())
                    .chatModel(ctx.getChatModel())
                    .build());
        });
    }

    // ===== 上下文注入 =====

    protected Plugin skillContextPlugin() {
        return new SimplePlugin("skill-context", c ->
                c.inject(AgentServiceKeys.INTERCEPTORS).add(SkillContextInterceptor.builder()
                        .skillManager(ctx.getSkillManager())
                        .build()));
    }

    protected Plugin subagentContextPlugin() {
        return new SimplePlugin("subagent-context", c ->
                c.inject(AgentServiceKeys.INTERCEPTORS).add(SubagentContextInterceptor.builder()
                        .definitionManager(ctx.getDefinitionManager())
                        .definition(ctx.getDefinition())
                        .build()));
    }

    protected Plugin environmentContextPlugin() {
        return new SimplePlugin("environment-context", c ->
                c.inject(AgentServiceKeys.INTERCEPTORS).add(EnvironmentContextInterceptor.builder().build()));
    }

    // ===== 基础横切拦截器 =====

    protected Plugin baseInterceptorsPlugin() {
        return new SimplePlugin("base-interceptors", c -> {
            var registry = c.inject(AgentServiceKeys.INTERCEPTORS);
            registry.add(new ToolCallBudgetInterceptor(ctx.getConfigManager().getMaxToolCalls()));
            registry.add(new PermissionInterceptor(ctx.getApprovalStrategies()));
            registry.add(new FileChangeRecorderInterceptor());
            registry.add(new TodoReminderInterceptor());
            registry.add(new ToolCardEventInterceptor());
            registry.add(new ToolResultOffloadInterceptor());
            // 回合结束异步提取长期记忆（仅主智能体，用户交互入口）
            registry.add(MemoryExtractionInterceptor.builder()
                    .sessionManager(ctx.getSessionManager())
                    .memoryStore(ctx.getMemoryStore())
                    .chatModel(ctx.getChatModel())
                    .build());
            // 自修改监视：Write/Edit 自身构成文件（SKILL.md / agent.md）后触发热重载
            registry.add(new SelfModifyWatchInterceptor(ctx.getSkillManager(), ctx.getDefinitionManager()));
        });
    }

    // ===== 工具集 =====

    protected Plugin toolsPlugin() {
        return new SimplePlugin("tools", c -> c.inject(AgentServiceKeys.TOOLS).addAll(buildBaseTools()));
    }

    /** 基础工具集：白名单工具 + 会话搜索 + 动态插件 + Creator 工具。 */
    protected List<ToolCallback> buildBaseTools() {
        List<ToolCallback> tools = new ArrayList<>(ctx.getToolkit().buildToolCallbacks(ctx.getDefinition()));
        tools.add(ConversationSearchTool.builder(ctx.getSessionManager()).build().toToolCallback());
        tools.add(CrossSessionSearchTool.builder(ctx.getSessionManager(), ctx.getUid()).build().toToolCallback());
        // 动态插件工具：白名单之后追加（豁免白名单，仅声明式组合安全工具）
        tools.addAll(ctx.getPluginRegistry().resolveToolCallbacks(ctx.getSession().id()));
        // Creator 工具集：运行中挂载/卸载/查看动态插件（自修改能力的插件形态）
        tools.add(PluginMountTool.builder().pluginRegistry(ctx.getPluginRegistry()).build().toToolCallback());
        tools.add(PluginUnmountTool.builder().pluginRegistry(ctx.getPluginRegistry()).build().toToolCallback());
        tools.add(PluginListTool.builder().pluginRegistry(ctx.getPluginRegistry()).build().toToolCallback());
        return tools;
    }
}
