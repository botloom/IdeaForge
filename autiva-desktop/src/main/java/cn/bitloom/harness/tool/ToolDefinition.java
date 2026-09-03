package cn.bitloom.harness.tool;

/**
 * 工具定义 — 名称、描述与入参 JSON Schema。
 *
 * @param name        工具名（LLM 调用时引用）
 * @param description 工具描述（提供给 LLM）
 * @param inputSchema 入参 JSON Schema 字符串
 */
public record ToolDefinition(String name, String description, String inputSchema) {

    public static ToolDefinition of(String name, String description, String inputSchema) {
        return new ToolDefinition(name, description, inputSchema);
    }
}
