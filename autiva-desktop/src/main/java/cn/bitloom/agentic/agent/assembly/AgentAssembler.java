package cn.bitloom.agentic.agent.assembly;

import cn.bitloom.agentic.agent.Agent;
import cn.bitloom.agentic.tool.runtime.RuntimeInspectTool;
import cn.bitloom.harness.kernel.PluginManager;
import cn.bitloom.harness.loop.LoopInterceptor;
import cn.bitloom.harness.tool.ToolCallback;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Agent 组装器 — 把 profile 插件集挂载到 {@link PluginManager}，收集各插件贡献的
 * 拦截器与工具，组装出 {@link Agent}。
 * <p>
 * 每次 assemble 都新建 PluginManager 并重新挂载插件集，插件 apply 内 new 的拦截器/
 * 工具实例即 per-Agent 隔离（无跨 session 串扰）。
 */
@Slf4j
public final class AgentAssembler {

    /**
     * 组装 Agent。
     *
     * @param agentId          智能体名
     * @param profile          模式插件集（code / work）
     * @param ctx              组装上下文（依赖集合）
     * @param systemPrompt     系统提示（完整，含 env block 等）
     * @param reactiveCompactor 紧急压缩器（可 null）
     */
    public Agent assemble(String agentId, AgentProfile profile, AgentAssemblyContext ctx,
                          String systemPrompt, Consumer<String> reactiveCompactor) {
        PluginManager pm = new PluginManager();
        InterceptorRegistry interceptors = new InterceptorRegistry();
        ToolRegistry tools = new ToolRegistry();
        // 组装期共享服务：插件经 inject 追加贡献
        pm.rootContext().provide(AgentServiceKeys.INTERCEPTORS, interceptors);
        pm.rootContext().provide(AgentServiceKeys.TOOLS, tools);

        pm.mountAll(profile.plugins());

        List<LoopInterceptor> allInterceptors = new ArrayList<>(interceptors.all());
        List<ToolCallback> allTools = new ArrayList<>(tools.all());

        // 自省工具（收尾）：让智能体看到自己的构成（Cordis "检查运行时插件树" 的对应物）
        if (ctx.getSkillManager() != null && ctx.getDefinitionManager() != null) {
            allTools.add(RuntimeInspectTool.builder()
                    .tools(List.copyOf(allTools))
                    .hooks(allInterceptors)
                    .skillManager(ctx.getSkillManager())
                    .definition(ctx.getDefinition())
                    .modelName(ctx.getModelName())
                    .build().toToolCallback());
        }

        log.info("[AgentAssembler] 组装 agent={} profile={}，拦截器数={}，工具数={}",
                agentId, profile.name(), allInterceptors.size(), allTools.size());
        return Agent.builder()
                .name(agentId)
                .definition(ctx.getDefinition())
                .model(ctx.getChatModel())
                .systemPrompt(systemPrompt)
                .tools(allTools)
                .interceptors(allInterceptors)
                .reactiveCompactor(reactiveCompactor)
                .build();
    }
}
