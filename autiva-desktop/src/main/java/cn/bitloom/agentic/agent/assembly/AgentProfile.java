package cn.bitloom.agentic.agent.assembly;

import cn.bitloom.harness.kernel.Plugin;

import java.util.List;

/**
 * Agent Profile — 对标 dsh Profile：一个模式的声明式插件集。
 * <p>
 * code 与 work 是两个不同的 profile（插件集）。{@link #plugins()} 返回的插件
 * 在每次 {@link AgentAssembler#assemble} 时重新挂载，因此插件在 apply 内 new 的
 * 拦截器/工具天然 per-Agent 隔离。
 */
public interface AgentProfile {

    /** profile 名（"code" / "work"，日志与自省用）。 */
    String name();

    /** 该 profile 的插件集（按依赖顺序排列）。 */
    List<Plugin> plugins();
}
