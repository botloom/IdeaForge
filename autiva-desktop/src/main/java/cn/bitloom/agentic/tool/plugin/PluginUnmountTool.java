package cn.bitloom.agentic.tool.plugin;

import cn.bitloom.agentic.agent.SelfModifiedEvent;
import cn.bitloom.agentic.plugin.PluginRegistry;
import cn.bitloom.harness.tool.AbstractTool;
import cn.bitloom.harness.tool.ToolResult;
import cn.bitloom.util.AppEvents;
import lombok.extern.slf4j.Slf4j;
import cn.bitloom.harness.tool.ToolContext;
import cn.bitloom.harness.tool.ToolParam;
import cn.bitloom.util.Assert;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 卸载动态插件工具（Creator 能力，可逆副作用）。
 * <p>
 * 卸载后执行插件的全部 disposer，插件提供的工具自下一轮对话起不再可用。
 * 卸载成功后发布 {@link SelfModifiedEvent}，触发 Agent 缓存驱逐重建。
 */
@Slf4j
public class PluginUnmountTool extends AbstractTool<PluginUnmountTool.Input> {

    private static final String DESCRIPTION = "卸载一个已挂载的动态插件（可用 PluginList 查看当前插件）。"
            + "卸载后该插件提供的工具自下一轮对话起不再可用。";

    private final PluginRegistry pluginRegistry;

    public record Input(
            @ToolParam(description = "要卸载的插件名", required = true) String pluginName
    ) {}

    private PluginUnmountTool(PluginRegistry pluginRegistry) {
        super("PluginUnmount", DESCRIPTION, Input.class);
        Assert.notNull(pluginRegistry, "pluginRegistry不能为null");
        this.pluginRegistry = pluginRegistry;
    }

    @Override
    public ToolResult execute(Input input, ToolContext context) {
        String sessionId = extractString(context, "sessionId");
        if (input == null || input.pluginName() == null || input.pluginName().isBlank()) {
            return ToolResult.error("缺少插件名（pluginName）");
        }

        boolean removed = pluginRegistry.unmount(input.pluginName());
        if (!removed) {
            return ToolResult.error("插件不存在: " + input.pluginName()
                    + "（可用 PluginList 查看当前已挂载插件）");
        }

        if (sessionId != null) {
            AppEvents.publish(new SelfModifiedEvent(sessionId,
                    "卸载动态插件: " + input.pluginName()));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("plugin", input.pluginName());
        return ToolResult.builder()
                .status(ToolResult.Status.SUCCESS)
                .message("插件已卸载: " + input.pluginName())
                .data(data)
                .rawOutput("插件 " + input.pluginName() + " 已卸载，其工具自下一轮对话起不再可用。")
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

        public PluginUnmountTool build() {
            return new PluginUnmountTool(pluginRegistry);
        }
    }
}
