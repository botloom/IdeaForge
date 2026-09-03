package cn.bitloom.agentic.plugin;

import cn.bitloom.harness.tool.ToolCallback;
import cn.bitloom.harness.tool.ToolContext;
import cn.bitloom.harness.tool.ToolDefinition;
import cn.bitloom.harness.tool.ToolResult;
import cn.bitloom.util.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 声明式工具回调 — 由 {@link ToolSpec} 驱动的动态 ToolCallback。
 * <p>
 * 不执行任意代码，只按声明组合既有工具：
 * <ul>
 *   <li>delegate：委托一个既有工具，参数经模板解析（{@code ${input.xxx}} 占位符）</li>
 *   <li>pipeline：顺序执行步骤，前步输出可注入后步参数（{@code ${stepN}} 占位符，超 4000 字符截断）</li>
 * </ul>
 * 引用的既有工具由 {@link PluginRegistry} 校验为非审批/非特权工具，
 * 委托调用直接执行（不经 Hook 管线，因此不允许包装审批门工具）。
 */
@Slf4j
public class DeclarativeToolCallback implements ToolCallback {

    private static final Pattern INPUT_REF = Pattern.compile("\\$\\{input\\.([A-Za-z0-9_]+)\\}");
    private static final Pattern STEP_REF = Pattern.compile("\\$\\{step(\\d+)\\}");
    private static final Pattern WHOLE_INPUT_REF = Pattern.compile("^\\$\\{input\\.([A-Za-z0-9_]+)\\}$");
    private static final int STEP_OUTPUT_MAX_CHARS = 4000;

    private final ToolSpec spec;
    private final Function<String, ToolCallback> delegateResolver;

    public DeclarativeToolCallback(ToolSpec spec, Function<String, ToolCallback> delegateResolver) {
        this.spec = spec;
        this.delegateResolver = delegateResolver;
    }

    @Override
    public @NonNull ToolDefinition definition() {
        String description = spec.description() != null ? spec.description() : "";
        String inputSchema;
        if (spec.parameters() == null || spec.parameters().isEmpty()) {
            inputSchema = "{\"type\":\"object\",\"properties\":{}}";
        } else {
            inputSchema = JsonUtils.toJson(JsonUtils.mapper().valueToTree(spec.parameters()));
        }
        return ToolDefinition.of(spec.name(), description, inputSchema);
    }

    @Override
    public @NonNull String call(@NonNull String toolInput, @Nullable ToolContext toolContext) {
        try {
            Map<String, Object> input = parseInput(toolInput);
            ToolHandler handler = spec.handler();
            if (handler == null) {
                return ToolResult.error("插件工具 [" + spec.name() + "] 缺少 handler 声明").toJson();
            }
            if ("pipeline".equalsIgnoreCase(handler.type())) {
                return executePipeline(handler, input, toolContext);
            }
            return executeDelegate(handler, input, toolContext);
        } catch (Exception e) {
            log.error("[PluginTool] 插件工具 {} 执行异常: {}", spec.name(), e.getMessage(), e);
            return ToolResult.error("插件工具执行异常: " + e.getMessage()).toJson();
        }
    }

    // ===== delegate =====

    private String executeDelegate(ToolHandler handler, Map<String, Object> input, ToolContext ctx)
            throws Exception {
        Map<String, Object> args = resolveArgs(handler.args(), input, Map.of());
        return invokeTool(handler.tool(), args, ctx);
    }

    // ===== pipeline =====

    private String executePipeline(ToolHandler handler, Map<String, Object> input, ToolContext ctx)
            throws Exception {
        Map<Integer, String> outputs = new LinkedHashMap<>();
        String lastOutput = null;
        int index = 1;
        for (PipelineStep step : handler.steps()) {
            Map<String, Object> args = resolveArgs(step.args(), input, outputs);
            String result = invokeTool(step.tool(), args, ctx);
            outputs.put(index, result);
            lastOutput = result;
            if (ToolResult.isToolResultJson(result)) {
                ToolResult parsed = ToolResult.fromJson(result);
                if (parsed != null && parsed.getStatus() == ToolResult.Status.ERROR) {
                    break;
                }
            }
            index++;
        }
        return lastOutput;
    }

    // ===== 执行与模板 =====

    private String invokeTool(String toolName, Map<String, Object> args, ToolContext ctx) throws Exception {
        if (toolName == null || toolName.isBlank()) {
            return ToolResult.error("插件步骤缺少工具名").toJson();
        }
        ToolCallback delegate = delegateResolver.apply(toolName);
        if (delegate == null) {
            return ToolResult.error("插件引用的工具不存在或不可用: " + toolName).toJson();
        }
        return delegate.call(JsonUtils.toJson(JsonUtils.mapper().valueToTree(args)), ctx);
    }

    private Map<String, Object> resolveArgs(Map<String, Object> args, Map<String, Object> input,
                                            Map<Integer, String> stepOutputs) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        if (args == null) {
            return resolved;
        }
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            if (entry.getValue() instanceof String template) {
                resolved.put(entry.getKey(), resolveTemplate(template, input, stepOutputs));
            } else {
                resolved.put(entry.getKey(), entry.getValue());
            }
        }
        return resolved;
    }

    private Object resolveTemplate(String template, Map<String, Object> input, Map<Integer, String> stepOutputs) {
        // 整串占位且值为非字符串类型时保留原始类型（数字/布尔）
        Matcher whole = WHOLE_INPUT_REF.matcher(template);
        if (whole.matches()) {
            Object value = input.get(whole.group(1));
            return value != null ? value : template;
        }
        String result = template;
        Matcher inputMatcher = INPUT_REF.matcher(result);
        StringBuilder sb = new StringBuilder();
        while (inputMatcher.find()) {
            Object value = input.get(inputMatcher.group(1));
            inputMatcher.appendReplacement(sb, Matcher.quoteReplacement(value != null ? String.valueOf(value) : ""));
        }
        inputMatcher.appendTail(sb);
        result = sb.toString();

        Matcher stepMatcher = STEP_REF.matcher(result);
        sb = new StringBuilder();
        while (stepMatcher.find()) {
            int stepIndex = Integer.parseInt(stepMatcher.group(1));
            String output = stepOutputs.getOrDefault(stepIndex, "");
            if (output.length() > STEP_OUTPUT_MAX_CHARS) {
                output = output.substring(0, STEP_OUTPUT_MAX_CHARS) + "...(truncated)";
            }
            stepMatcher.appendReplacement(sb, Matcher.quoteReplacement(output));
        }
        stepMatcher.appendTail(sb);
        return sb.toString();
    }

    private Map<String, Object> parseInput(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> map = JsonUtils.mapper().readValue(toolInput,
                    new TypeReference<LinkedHashMap<String, Object>>() {});
            return map != null ? map : Map.of();
        } catch (Exception e) {
            log.warn("[PluginTool] 插件工具 {} 入参解析失败，按空参处理: {}", spec.name(), toolInput);
            return Map.of();
        }
    }
}
