package cn.bitloom.harness.llm;

/**
 * 模型发起的一次工具调用。
 *
 * @param id        调用 ID（工具结果回填时的关联键，可能为 null——部分兼容端不返回）
 * @param name      工具名
 * @param arguments 参数 JSON 字符串（原始文本，未解析）
 */
public record ToolCall(String id, String name, String arguments) {

    public static ToolCall of(String id, String name, String arguments) {
        return new ToolCall(id, name, arguments != null ? arguments : "{}");
    }
}
