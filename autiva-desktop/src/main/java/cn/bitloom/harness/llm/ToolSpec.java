package cn.bitloom.harness.llm;

/**
 * 工具规格 — 提供给模型的工具声明（JSON Schema 形式）。
 * dsh 中由 core/tools 注册表产出、llm 适配器消费。
 *
 * @param name        工具名
 * @param description 工具描述
 * @param inputSchema 入参 JSON Schema 字符串
 */
public record ToolSpec(String name, String description, String inputSchema) {

    public static ToolSpec of(String name, String description, String inputSchema) {
        return new ToolSpec(name, description, inputSchema);
    }
}
