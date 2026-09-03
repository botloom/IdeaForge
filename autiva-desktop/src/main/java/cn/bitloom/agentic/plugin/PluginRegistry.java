package cn.bitloom.agentic.plugin;

import cn.bitloom.agentic.tool.Toolkit;
import cn.bitloom.harness.tool.ToolCallback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 动态插件注册表 — 内存态插件的挂载、卸载与生命周期管理
 * （Cordis 插件内核的声明式子集：可逆注册 + 作用域化清理）。
 * <p>
 * 安全约束：
 * <ul>
 *   <li>插件工具只能引用既有工具目录中的非审批/非特权工具（防止绕过审批管线）</li>
 *   <li>插件名/工具名冲突检测（含与内置工具的冲突）</li>
 *   <li>数量上限：单 session 可见插件 10 个，单插件工具 20 个，pipeline 步骤 5 步</li>
 *   <li>仅存在于进程内存，重启即消失</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class PluginRegistry {

    public static final int MAX_PLUGINS_PER_SESSION = 10;
    public static final int MAX_TOOLS_PER_PLUGIN = 20;
    public static final int MAX_PIPELINE_STEPS = 5;

    /** 动态插件不可引用的工具（审批门/特权工具，防止包装绕过审批或递归挂载） */
    private static final Set<String> BLOCKED_REFERENCES = Set.of(
            "Write", "Edit", "Command", "Process",
            "Task", "TaskOutput", "McpConnect",
            "PluginMount", "PluginUnmount", "PluginList",
            "SpawnTeammate", "TeammateShutdown", "Workflow", "GoalSet");

    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,63}$");

    private final Toolkit toolkit;

    /** 插件名 → 注册项 */
    private final Map<String, PluginRegistration> plugins = new ConcurrentHashMap<>();

    /**
     * 挂载插件。校验失败抛 {@link IllegalArgumentException}（含可读原因，供工具层返回给 LLM）。
     *
     * @param plugin  插件声明
     * @param scope   作用域
     * @param ownerId SESSION 作用域时的 sessionId；AGENT 作用域传 null
     */
    public synchronized PluginRegistration mount(DynamicPlugin plugin, PluginScope scope, String ownerId) {
        validate(plugin, scope, ownerId);
        List<DeclarativeToolCallback> callbacks = plugin.tools().stream()
                .map(spec -> new DeclarativeToolCallback(spec, this::resolveDelegate))
                .toList();
        PluginRegistration registration = new PluginRegistration(plugin, scope, ownerId, callbacks);
        plugins.put(plugin.name(), registration);
        log.info("[PluginRegistry] 挂载插件: name={}, scope={}, tools={}",
                plugin.name(), scope, plugin.tools().size());
        return registration;
    }

    /**
     * 卸载插件，执行全部 disposer（可逆清理）。
     *
     * @return true 表示卸载成功；插件不存在返回 false
     */
    public synchronized boolean unmount(String pluginName) {
        PluginRegistration registration = plugins.remove(pluginName);
        if (registration == null) {
            return false;
        }
        for (Runnable disposer : registration.getDisposers()) {
            try {
                disposer.run();
            } catch (Exception e) {
                log.warn("[PluginRegistry] 插件 {} 的 disposer 执行失败", pluginName, e);
            }
        }
        log.info("[PluginRegistry] 卸载插件: name={}", pluginName);
        return true;
    }

    /**
     * 返回指定 session 可见的动态工具回调（AGENT 作用域 + 该 session 的 SESSION 作用域）。
     */
    public List<ToolCallback> resolveToolCallbacks(String sessionId) {
        List<ToolCallback> result = new ArrayList<>();
        for (PluginRegistration registration : plugins.values()) {
            if (sessionId == null || registration.isVisibleTo(sessionId)) {
                result.addAll(registration.getCallbacks());
            }
        }
        return result;
    }

    /**
     * 清理指定 session 作用域的全部插件（会话关闭时调用）。
     */
    public synchronized void disposeSession(String sessionId) {
        List<String> toRemove = plugins.values().stream()
                .filter(r -> r.getScope() == PluginScope.SESSION && sessionId != null && sessionId.equals(r.getOwnerId()))
                .map(r -> r.getPlugin().name())
                .toList();
        for (String name : toRemove) {
            unmount(name);
        }
    }

    /**
     * 列出指定 session 可见的插件描述（名称/作用域/工具清单）。
     */
    public List<Map<String, Object>> listPlugins(String sessionId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (PluginRegistration registration : plugins.values()) {
            if (sessionId == null || registration.isVisibleTo(sessionId)) {
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("name", registration.getPlugin().name());
                item.put("description", registration.getPlugin().description());
                item.put("scope", registration.getScope().name());
                item.put("tools", registration.getPlugin().tools().stream().map(ToolSpec::name).toList());
                result.add(item);
            }
        }
        return result;
    }

    /**
     * 是否为已挂载插件提供的工具名。
     */
    public boolean isPluginTool(String toolName) {
        return plugins.values().stream()
                .flatMap(r -> r.getPlugin().tools().stream())
                .anyMatch(spec -> spec.name().equals(toolName));
    }

    private ToolCallback resolveDelegate(String toolName) {
        return toolkit.buildToolByName(toolName);
    }

    // ===== 校验 =====

    private void validate(DynamicPlugin plugin, PluginScope scope, String ownerId) {
        if (plugin == null || isBlank(plugin.name())) {
            throw new IllegalArgumentException("插件名不能为空");
        }
        if (scope == PluginScope.SESSION && isBlank(ownerId)) {
            throw new IllegalArgumentException("SESSION 作用域插件必须提供 sessionId");
        }
        if (plugins.containsKey(plugin.name())) {
            throw new IllegalArgumentException("插件已存在: " + plugin.name() + "，请先卸载或换名");
        }
        long visible = plugins.values().stream()
                .filter(r -> r.isVisibleTo(ownerId))
                .count();
        if (visible >= MAX_PLUGINS_PER_SESSION) {
            throw new IllegalArgumentException("可见插件数已达上限 " + MAX_PLUGINS_PER_SESSION);
        }
        if (plugin.tools() == null || plugin.tools().isEmpty()) {
            throw new IllegalArgumentException("插件必须至少声明一个工具");
        }
        if (plugin.tools().size() > MAX_TOOLS_PER_PLUGIN) {
            throw new IllegalArgumentException("单插件工具数超过上限 " + MAX_TOOLS_PER_PLUGIN);
        }
        for (ToolSpec spec : plugin.tools()) {
            validateToolSpec(spec);
        }
    }

    private void validateToolSpec(ToolSpec spec) {
        if (spec == null || isBlank(spec.name()) || !TOOL_NAME_PATTERN.matcher(spec.name()).matches()) {
            throw new IllegalArgumentException("工具名非法（需字母开头，仅含字母/数字/下划线/连字符）: "
                    + (spec == null ? null : spec.name()));
        }
        if (isBlank(spec.description())) {
            throw new IllegalArgumentException("工具 " + spec.name() + " 缺少 description");
        }
        if (toolkit.buildToolByName(spec.name()) != null) {
            throw new IllegalArgumentException("工具名与内置工具冲突: " + spec.name());
        }
        if (plugins.values().stream()
                .flatMap(r -> r.getPlugin().tools().stream())
                .anyMatch(existing -> existing.name().equals(spec.name()))) {
            throw new IllegalArgumentException("工具名与已挂载插件冲突: " + spec.name());
        }
        ToolHandler handler = spec.handler();
        if (handler == null) {
            throw new IllegalArgumentException("工具 " + spec.name() + " 缺少 handler");
        }
        if ("pipeline".equalsIgnoreCase(handler.type())) {
            if (handler.steps() == null || handler.steps().isEmpty()) {
                throw new IllegalArgumentException("工具 " + spec.name() + " 的 pipeline 不能为空");
            }
            if (handler.steps().size() > MAX_PIPELINE_STEPS) {
                throw new IllegalArgumentException("工具 " + spec.name() + " 的 pipeline 步骤超过上限 " + MAX_PIPELINE_STEPS);
            }
            for (PipelineStep step : handler.steps()) {
                checkReferencedTool(spec.name(), step.tool());
            }
        } else if ("delegate".equalsIgnoreCase(handler.type())) {
            checkReferencedTool(spec.name(), handler.tool());
        } else {
            throw new IllegalArgumentException("工具 " + spec.name() + " 的 handler.type 必须是 delegate 或 pipeline");
        }
    }

    private void checkReferencedTool(String toolName, String referenced) {
        if (isBlank(referenced)) {
            throw new IllegalArgumentException("工具 " + toolName + " 引用的工具名为空");
        }
        if (BLOCKED_REFERENCES.contains(referenced)) {
            throw new IllegalArgumentException("工具 " + toolName + " 不可引用审批/特权工具: " + referenced);
        }
        if (toolkit.buildToolByName(referenced) == null) {
            throw new IllegalArgumentException("工具 " + toolName + " 引用的工具不存在: " + referenced);
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
