package cn.bitloom.agentic.agent.assembly;

import cn.bitloom.harness.kernel.HarnessContext;
import cn.bitloom.harness.kernel.Plugin;

import java.util.function.Consumer;

/**
 * 函数式插件 — 以 lambda 定义 {@link Plugin}，避免为每个关注点建独立插件类。
 * <p>
 * 典型用法（在 Profile 内联声明）：
 * <pre>
 * new SimplePlugin("session-memory", ctx ->
 *     ctx.inject(AgentServiceKeys.INTERCEPTORS).add(new SessionMemoryInterceptor(...)));
 * </pre>
 */
public final class SimplePlugin implements Plugin {

    private final String name;
    private final Consumer<HarnessContext> applier;

    public SimplePlugin(String name, Consumer<HarnessContext> applier) {
        this.name = name;
        this.applier = applier;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void apply(HarnessContext ctx) {
        applier.accept(ctx);
    }
}
