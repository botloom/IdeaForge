package cn.bitloom.agentic.util;

import cn.bitloom.harness.llm.ChatMessage;

import java.util.Map;

public class MessageUtil {

    /**
     * 构建异常兜底消息（统一文案，避免重复）
     */
    public static ChatMessage buildFallbackMessage() {
        return ChatMessage.assistant("""
                ### 呜呜呜，小脑袋打了个盹儿…

                出了一点小问题，暂时无法回复你 >_<

                **试试以下方法：**
                - **清空消息**后重新发送
                - 如果还是不行，**重启应用**再试试

                > 抱歉给你添麻烦啦～""", null, null, "STOP");
    }

    public static Map<String, Object> fallbackMetadata() {
        return Map.of("finishReason", "STOP");
    }
}
