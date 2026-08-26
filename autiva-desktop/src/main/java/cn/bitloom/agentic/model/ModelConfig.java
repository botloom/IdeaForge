package cn.bitloom.agentic.model;

/**
 * 模型配置：设置页可添加多个，对话区按 id 选择。
 *
 * @param id             唯一标识（配置 key）
 * @param name           显示名称（对话区下拉展示）
 * @param baseUrl        OpenAI 兼容 API 基础地址
 * @param apiKey         API Key
 * @param chatModel      聊天模型名称
 * @param completionsPath API 补全路径
 */
public record ModelConfig(String id, String name, String baseUrl, String apiKey,
                          String chatModel, String completionsPath) {
}
