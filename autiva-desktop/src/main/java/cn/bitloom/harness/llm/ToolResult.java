package cn.bitloom.harness.llm;

/**
 * 工具执行结果（回填给模型的 TOOL 角色消息负载）。
 *
 * @param id      对应的 ToolCall ID
 * @param name    工具名
 * @param content 结果内容（通常是 ToolResult JSON 字符串）
 */
public record ToolResult(String id, String name, String content) {
}
