package cn.bitloom.harness.llm;

import java.util.Map;

/**
 * 模型调用选项（OpenAI 兼容子集）。
 *
 * @param model     模型名（null 时用客户端默认）
 * @param temperature 采样温度
 * @param maxTokens 最大输出 token
 * @param extraBody 附加请求体字段（如 thinking 开关），直合并到 chat/completions 顶层
 */
public record ChatOptions(String model, Double temperature, Integer maxTokens, Map<String, Object> extraBody) {

    public static ChatOptions of(String model) {
        return new ChatOptions(model, null, null, null);
    }

    public static ChatOptions of(String model, Map<String, Object> extraBody) {
        return new ChatOptions(model, null, null, extraBody);
    }
}
