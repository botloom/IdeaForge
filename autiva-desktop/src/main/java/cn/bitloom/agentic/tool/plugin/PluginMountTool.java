package cn.bitloom.agentic.tool.plugin;

import cn.bitloom.agentic.agent.SelfModifiedEvent;
import cn.bitloom.agentic.plugin.DynamicPlugin;
import cn.bitloom.agentic.plugin.PluginRegistry;
import cn.bitloom.agentic.plugin.PluginRegistration;
import cn.bitloom.agentic.plugin.PluginScope;
import cn.bitloom.agentic.plugin.ToolSpec;
import cn.bitloom.harness.tool.AbstractTool;
import cn.bitloom.harness.tool.ToolResult;
import cn.bitloom.util.JsonUtils;
import cn.bitloom.util.AppEvents;
import lombok.extern.slf4j.Slf4j;
import cn.bitloom.harness.tool.ToolContext;
import cn.bitloom.harness.tool.ToolParam;
import cn.bitloom.util.Assert;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 挂载动态插件工具（Creator 能力）。
 * <p>
 * 智能体在运行中为自己临时增加工具：插件仅存在于内存，会话结束（session 作用域）
 * 或重启后自动消失。插件工具只能声明式组合既有安全工具（delegate 委托 / pipeline 流水线），
 * 不能执行任意代码。
 * <p>
 * 挂载成功后发布 {@link SelfModifiedEvent}，下一轮对话重建 Agent 时新工具即生效。
 */
@Slf4j
public class PluginMountTool extends AbstractTool<PluginMountTool.Input> {

    private static final String DESCRIPTION = "挂载一个临时动态插件，为自己临时增加新工具（仅内存态，重启消失）。"
            + "插件声明为 JSON：{\"name\":\"插件名\",\"description\":\"描述\",\"tools\":[{"
            + "\"name\":\"工具名\",\"description\":\"描述\",\"parameters\":{JSON Schema},"
            + "\"handler\":{\"type\":\"delegate\",\"tool\":\"既有工具名\",\"args\":{\"参数\":\"值或${input.字段}\"}}}]}}。"
            + "handler 也支持 pipeline 类型：{\"type\":\"pipeline\",\"steps\":[{\"tool\":\"...\",\"args\":{\"...\":\"${step1}\"}}]}（最多5步）。"
            + "只能引用安全工具（不可引用 Write/Edit/Command 等）。挂载成功后下一轮对话即可使用新工具。";

    private final PluginRegistry pluginRegistry;

    public record Input(
            @ToolParam(description = "插件声明 JSON 字符串", required = true) String pluginJson,
            @ToolParam(description = "作用域：session（默认，会话结束自动卸载）| agent（进程级）", required = false) String scope
    ) {}

    private PluginMountTool(PluginRegistry pluginRegistry) {
        super("PluginMount", DESCRIPTION, Input.class);
        Assert.notNull(pluginRegistry, "pluginRegistry不能为null");
        this.pluginRegistry = pluginRegistry;
    }

    @Override
    public ToolResult execute(Input input, ToolContext context) {
        String sessionId = extractString(context, "sessionId");
        if (input == null || input.pluginJson() == null || input.pluginJson().isBlank()) {
            return ToolResult.error("缺少插件声明（pluginJson）");
        }

        DynamicPlugin plugin;
        try {
            plugin = JsonUtils.mapper().readValue(input.pluginJson(), DynamicPlugin.class);
        } catch (Exception e) {
            return ToolResult.error("插件声明 JSON 解析失败: " + e.getMessage());
        }

        PluginScope scope = "agent".equalsIgnoreCase(input.scope()) ? PluginScope.AGENT : PluginScope.SESSION;

        PluginRegistration registration;
        try {
            registration = pluginRegistry.mount(plugin, scope, sessionId);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("插件挂载被拒绝: " + e.getMessage());
        }

        // 下一轮对话重建 Agent，新工具生效
        if (sessionId != null) {
            AppEvents.publish(new SelfModifiedEvent(sessionId,
                    "挂载动态插件: " + plugin.name()));
        }

        List<String> toolNames = registration.getPlugin().tools().stream().map(ToolSpec::name).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("plugin", plugin.name());
        data.put("scope", scope.name());
        data.put("tools", toolNames);
        return ToolResult.builder()
                .status(ToolResult.Status.SUCCESS)
                .message("插件已挂载: " + plugin.name() + "，新工具自下一轮对话起可用")
                .data(data)
                .rawOutput("插件 " + plugin.name() + " 已挂载（作用域 " + scope.name() + "），"
                        + "提供工具: " + String.join(", ", toolNames)
                        + "。新工具自下一轮对话起可用。")
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

        public PluginMountTool build() {
            return new PluginMountTool(pluginRegistry);
        }
    }
}
