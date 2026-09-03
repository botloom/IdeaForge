package cn.bitloom.harness.tool;

import cn.bitloom.util.JsonUtils;
import lombok.Getter;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 工具抽象基类，借鉴 AgentScope 的 ToolBase 设计模式（替换 spring-ai 版本的 AbstractTool）。
 * <p>
 * 所有工具统一继承此类，通过泛型 {@code I} 指定输入参数 record 类型，
 * 实现 {@link #execute} 方法定义执行逻辑，通过 {@link #toToolCallback()} 转换为
 * harness 的 {@link ToolCallback}（自动经 {@link JsonSchemaGenerator} 生成 schema）。
 *
 * @param <I> 输入参数 record 类型，字段使用 {@link ToolParam} 注解
 */
@Getter
public abstract class AbstractTool<I> {

    private final @NonNull String name;
    private final @NonNull String description;
    private final @NonNull Class<I> inputType;

    protected AbstractTool(@NonNull String name, @NonNull String description,
                           @NonNull Class<I> inputType) {
        this.name = name;
        this.description = description;
        this.inputType = inputType;
    }

    /**
     * 工具执行逻辑，子类实现。
     *
     * @param input   输入参数 record 实例
     * @param context 工具上下文，可从中获取 sessionId / projectPath / eventSink 等
     * @return 统一返回值
     */
    public abstract @NonNull ToolResult execute(@NonNull I input, @Nullable ToolContext context);

    /**
     * 转换为 harness 的 {@link ToolCallback}，用于注册到 AgentLoop。
     * <p>
     * 自动将 {@link #execute} 的 ToolResult 返回值转为 JSON 字符串。
     */
    public final ToolCallback toToolCallback() {
        return new ToolCallback() {
            private final ToolDefinition definition = ToolDefinition.of(
                    name, description, JsonSchemaGenerator.generate(inputType));

            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public String call(String inputJson, ToolContext context) {
                String raw = inputJson == null || inputJson.isBlank() ? "{}" : inputJson;
                I input;
                try {
                    input = JsonUtils.fromJson(raw, inputType);
                } catch (Exception e) {
                    throw new IllegalStateException("参数 JSON 解析失败: " + e.getMessage(), e);
                }
                if (input == null) {
                    input = JsonUtils.mapper().convertValue(java.util.Map.of(), inputType);
                }
                return execute(input, context).toJson();
            }
        };
    }
}
