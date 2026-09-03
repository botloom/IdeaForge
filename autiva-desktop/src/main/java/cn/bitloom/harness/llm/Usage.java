package cn.bitloom.harness.llm;

/**
 * token 用量统计（流式终帧或非流式响应携带）。
 */
public record Usage(Long promptTokens, Long completionTokens, Long totalTokens) {

    public static final Usage EMPTY = new Usage(null, null, null);

    public boolean isEmpty() {
        return promptTokens == null && completionTokens == null && totalTokens == null;
    }
}
