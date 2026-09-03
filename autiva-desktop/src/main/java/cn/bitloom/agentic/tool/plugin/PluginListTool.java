package cn.bitloom.agentic.tool.plugin;

import cn.bitloom.agentic.plugin.PluginRegistry;
import cn.bitloom.harness.tool.AbstractTool;
import cn.bitloom.harness.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import cn.bitloom.harness.tool.ToolContext;
import cn.bitloom.util.Assert;

import java.util.List;
import java.util.Map;

/**
 * 列出当前可见动态插件工具（Creator 能力的自省入口）。
 * <p>
 * 返回当前 session 可见的全部动态插件（AGENT 作用域 + 本 session 的 SESSION 作用域），
 * 含名称、描述、作用域与工具清单。无已挂载插件时返回空列表提示。
 */
@Slf4j
public class PluginListTool extends AbstractTool<PluginListTool.Input> {

    private static final String DESCRIPTION = "列出当前已挂载的动态插件（名称/描述/作用域/工具清单）。"
            + "无参数。当前没有动态插件时返回空。";

    private final PluginRegistry pluginRegistry;

    public record Input() {}

    private PluginListTool(PluginRegistry pluginRegistry) {
        super("PluginList", DESCRIPTION, Input.class);
        Assert.notNull(pluginRegistry, "pluginRegistry不能为null");
        this.pluginRegistry = pluginRegistry;
    }

    @Override
    public ToolResult execute(Input input, ToolContext context) {
        String sessionId = extractString(context, "sessionId");
        List<Map<String, Object>> plugins = pluginRegistry.listPlugins(sessionId);

        if (plugins.isEmpty()) {
            return ToolResult.builder()
                    .status(ToolResult.Status.SUCCESS)
                    .message("当前没有已挂载的动态插件")
                    .rawOutput("当前没有已挂载的动态插件。可用 PluginMount 挂载新插件。")
                    .build();
        }

        StringBuilder sb = new StringBuilder("已挂载动态插件 " + plugins.size() + " 个:\n");
        for (Map<String, Object> plugin : plugins) {
            sb.append("- ").append(plugin.get("name"))
                    .append("（作用域 ").append(plugin.get("scope")).append("）：")
                    .append(plugin.get("description")).append("\n")
                    .append("  工具: ").append(String.join(", ",
                            (List<String>) plugin.get("tools"))).append("\n");
        }

        return ToolResult.builder()
                .status(ToolResult.Status.SUCCESS)
                .message("当前已挂载 " + plugins.size() + " 个动态插件")
                .data(Map.of("plugins", plugins))
                .rawOutput(sb.toString())
                .build();
    }

    private String extractString(ToolContext context, String key) {
        if (context == null || context.getContext() == null) {
            return null;
        }
        Object value = context.getContext().get(key);
        return value instanceof String s ? s : null;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private PluginRegistry pluginRegistry;

        public Builder pluginRegistry(PluginRegistry pluginRegistry) {
            this.pluginRegistry = pluginRegistry;
            return this;
        }

        public PluginListTool build() {
            return new PluginListTool(pluginRegistry);
        }
    }
}
