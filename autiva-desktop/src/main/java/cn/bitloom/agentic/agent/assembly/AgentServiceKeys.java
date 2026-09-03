package cn.bitloom.agentic.agent.assembly;

import cn.bitloom.harness.kernel.ServiceKey;

/**
 * Agent 组装服务键 — 插件化组装期间在 {@link cn.bitloom.harness.kernel.HarnessContext}
 * 上共享的契约键。
 */
public final class AgentServiceKeys {

    /** 拦截器收集器：各插件追加自己贡献的 LoopInterceptor。 */
    public static final ServiceKey<InterceptorRegistry> INTERCEPTORS =
            ServiceKey.of("agent.interceptors", InterceptorRegistry.class);

    /** 工具收集器：各插件追加自己贡献的 ToolCallback。 */
    public static final ServiceKey<ToolRegistry> TOOLS =
            ServiceKey.of("agent.tools", ToolRegistry.class);

    private AgentServiceKeys() {
    }
}
