package cn.bitloom.agentic.agent.assembly;

import cn.bitloom.harness.loop.LoopInterceptor;

import java.util.ArrayList;
import java.util.List;

/**
 * 拦截器收集器 — 插件化组装期间各插件追加 {@link LoopInterceptor} 的共享容器。
 * <p>
 * 保持追加顺序（即插件挂载顺序），由 {@link AgentAssembler} 在组装末尾一次性取出。
 */
public final class InterceptorRegistry {

    private final List<LoopInterceptor> items = new ArrayList<>();

    public void add(LoopInterceptor interceptor) {
        if (interceptor != null) {
            items.add(interceptor);
        }
    }

    public void addAll(List<LoopInterceptor> interceptors) {
        if (interceptors != null) {
            items.addAll(interceptors);
        }
    }

    public List<LoopInterceptor> all() {
        return List.copyOf(items);
    }
}
