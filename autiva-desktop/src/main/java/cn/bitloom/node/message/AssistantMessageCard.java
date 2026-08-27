package cn.bitloom.node.message;

import cn.bitloom.util.MarkdownFxRenderer;
import org.springframework.ai.chat.messages.MessageType;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.scene.Node;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * AI 正文消息卡片：只承载回复正文。
 * <p>
 * 流式期间单 TextFlow 纯文本输出（节流合并同一 FX 脉冲的多次 chunk），
 * 流式结束后整体替换为 Markdown 渲染结果。思考与工具调用由独立卡片承载
 * （ReasoningCard / ToolCallCard），各自直接加入虚拟列表按事件顺序展示。
 */
@Getter
@Slf4j
public class AssistantMessageCard extends MessageCard {

    private static final String FONT_FAMILY = "\"SF Pro Text\", -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif";

    private final StringProperty content = new SimpleStringProperty("");
    private final ObjectProperty<String> finishReason = new SimpleObjectProperty<>(null);
    private final BooleanProperty streaming = new SimpleBooleanProperty(false);

    // 流式期间复用的组件
    private TextFlow streamingContainer = null;
    private Text streamingText = null;

    private final StringBuilder accumulator = new StringBuilder();
    private boolean isStreamingActive = false;

    @Setter
    private Consumer<String> onContentChanged;

    /** 节流标志：同一 FX 脉冲内多次 chunk 只调度一次 flush，避免逐 chunk 触发 setText+reflow */
    private boolean textUpdateScheduled = false;

    public AssistantMessageCard() {
        this.getStyleClass().add("chat-message");
        this.getStyleClass().add("chat-message--assistant");

        // 预初始化流式容器，确保 card 有非零 prefHeight
        initStreamingContainer();

        // 监听 streamingProperty，流式结束时触发 Markdown 渲染
        streaming.addListener((obs, oldVal, newVal) -> {
            if (!newVal && getContent() != null) {
                renderMarkdown(getContent());
                if (onContentChanged != null) {
                    onContentChanged.accept(getContent());
                }
            }
        });
    }

    /**
     * 带初始内容构造（用于历史消息 / 一次性输出）。
     * content.set 触发 listener 自动渲染 Markdown。
     */
    public AssistantMessageCard(String initialContent, String finishReason) {
        this();
        if (finishReason != null) {
            this.finishReason.set(finishReason);
        }
        if (initialContent != null) {
            this.content.set(initialContent); // listener 检测到 !isStreaming() → renderMarkdown
        }
    }

    @Override
    public MessageType getMessageType() {
        return MessageType.ASSISTANT;
    }

    @Override
    public String getContent() {
        return content.get();
    }

    public StringProperty contentProperty() {
        return content;
    }

    public ObjectProperty<String> finishReasonProperty() {
        return finishReason;
    }

    public String getFinishReason() {
        return finishReason.get();
    }

    public BooleanProperty streamingProperty() {
        return streaming;
    }

    public boolean isStreaming() {
        return streaming.get();
    }

    public void setStreaming(boolean value) {
        streaming.set(value);
    }

    /**
     * 累积流式内容。自动设置 streaming=true，调度节流式 UI 更新。
     */
    public void appendContent(String chunk) {
        if (!isStreamingActive) {
            isStreamingActive = true;
            streaming.set(true);
        }
        accumulator.append(chunk != null ? chunk : "");
        scheduleFlush();
    }

    /**
     * 结束流式输出。取消 pending flush，设置 content，触发 Markdown 渲染。
     * 累积内容为空时 content 置 null（供外部判断是否移除空卡）。
     */
    public void complete(String reason) {
        textUpdateScheduled = false;
        isStreamingActive = false;
        content.set(accumulator.isEmpty() ? null : accumulator.toString());
        finishReason.set(reason);
        streaming.set(false); // 触发 streaming listener → renderMarkdown
    }

    /** 判断正文是否为空（供外部决定是否移除空卡）。 */
    public boolean isValid() {
        String c = content.get();
        return c == null || c.isBlank();
    }

    /**
     * 重开已完成卡片继续流式（相邻正文卡片合并场景）：
     * 恢复流式 TextFlow 容器，累计内容从原 content 续接（两段文本间以空行分隔），
     * 后续 appendContent/complete 复用现有流式逻辑，complete 时整体重新渲染 Markdown。
     */
    public void reopen() {
        String previous = getContent() == null ? "" : getContent();
        accumulator.setLength(0);
        accumulator.append(previous);
        if (!previous.isBlank()) {
            accumulator.append("\n\n");
        }
        isStreamingActive = true;
        streaming.set(true);
        initStreamingContainer();
    }

    private void scheduleFlush() {
        if (textUpdateScheduled) return;
        textUpdateScheduled = true;
        Platform.runLater(this::flushStreamingText);
    }

    private void flushStreamingText() {
        if (!isStreamingActive) {
            textUpdateScheduled = false;
            return;
        }
        textUpdateScheduled = false;
        String full = accumulator.toString();
        if (streamingText == null) {
            initStreamingContainer();
        }
        streamingText.setText(full);
        if (onContentChanged != null) {
            onContentChanged.accept(full);
        }
    }

    private void initStreamingContainer() {
        streamingContainer = new TextFlow();
        streamingContainer.getStyleClass().add("md-paragraph");
        streamingContainer.getStyleClass().add("chat-message__content");
        streamingContainer.setMaxWidth(Double.MAX_VALUE);

        streamingText = new Text("");
        streamingText.setFont(Font.font(FONT_FAMILY, 15));
        streamingContainer.getChildren().add(streamingText);

        this.getChildren().setAll(streamingContainer);
        this.getStyleClass().add("chat-message--streaming");
    }

    private void renderMarkdown(String content) {
        this.getChildren().clear();
        this.getStyleClass().remove("chat-message--streaming");

        streamingContainer = null;
        streamingText = null;

        if (content == null || content.isBlank()) {
            return;
        }

        try {
            VBox rendered = MarkdownFxRenderer.render(content);
            List<Node> childrenCopy = new ArrayList<>(rendered.getChildren());
            for (Node child : childrenCopy) {
                if (child instanceof TextFlow tf) {
                    tf.setMaxWidth(Double.MAX_VALUE);
                    tf.getStyleClass().add("chat-message__content");
                } else if (child instanceof Region region) {
                    region.setMaxWidth(Double.MAX_VALUE);
                }
                this.getChildren().add(child);
            }
        } catch (Exception e) {
            log.error("Markdown渲染失败，使用TextFlow回退", e);
            TextFlow textFlow = new TextFlow();
            textFlow.getStyleClass().add("chat-message__content");
            Text text = new Text(content);
            text.setFont(Font.font(FONT_FAMILY, 15));
            textFlow.getChildren().add(text);
            this.getChildren().add(textFlow);
        }
    }
}
