package cn.bitloom.agentic.agent.assembly;

import cn.bitloom.harness.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具收集器 — 插件化组装期间各插件追加 {@link ToolCallback} 的共享容器。
 * <p>
 * 保持追加顺序，由 {@link AgentAssembler} 在组装末尾一次性取出。
 */
public final class ToolRegistry {

    private final List<ToolCallback> items = new ArrayList<>();

    public void add(ToolCallback tool) {
        if (tool != null) {
            items.add(tool);
        }
    }

    public void addAll(List<ToolCallback> tools) {
        if (tools != null) {
            items.addAll(tools);
        }
    }

    public List<ToolCallback> all() {
        return List.copyOf(items);
    }
}
