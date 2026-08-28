package cn.bitloom.node.message;

import cn.bitloom.util.MarkdownFxRenderer;
import javafx.scene.Node;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import lombok.Setter;
import org.springframework.ai.chat.messages.MessageType;

import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * 思考内容子块：嵌入 {@link ReasoningProcessCard} 折叠容器内的一段思考正文，
 * 不再作为独立列表项、无自身折叠头（展开/折叠由外层容器统一管理）。
 * <p>
 * 流式期间纯文本 TextFlow（节流更新由外层 chunk 覆盖语义天然合并）；
 * 段落结束（{@link #finalizeSegment()}）时将累积内容定格渲染为 Markdown。
 * 同一轮次内跨工具调用的多段思考合并到容器内相邻子块（{@link #beginNewSegment()}）。
 */
public class ReasoningCard extends MessageCard {

    private static final String FONT_FAMILY = "\"SF Pro Text\", -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif";

    private final StringBuilder acc = new StringBuilder();
    /** 思考正文容器：流式时承载 TextFlow，定格后承载 Markdown 块 */
    private final VBox body;
    private final TextFlow streamingFlow;
    private final Text text;
    /** 已合并的前段思考内容（同轮多段合并时冻结），显示文本 = prefix + 当前段 */
    private String prefix = "";

    /** 内容变化回调（由外层容器注入，转发触发滚动 / Flowless 布局刷新）。 */
    @Setter
    private Consumer<String> onContentChanged;

    public ReasoningCard() {
        this.getStyleClass().add("reasoning-segment");

        body = new VBox(4);
        body.setMaxWidth(Double.MAX_VALUE);

        streamingFlow = new TextFlow();
        streamingFlow.getStyleClass().add("md-paragraph");
        text = new Text();
        text.getStyleClass().add("thinking-text");
        text.setFont(Font.font(FONT_FAMILY, FontPosture.ITALIC, 14));
        streamingFlow.getChildren().add(text);
        body.getChildren().add(streamingFlow);

        this.setMaxWidth(Double.MAX_VALUE);
        this.getChildren().add(body);
    }

    /**
     * 更新当前段思考文本（覆盖语义：每 chunk 携带本段从头到当前的完整文本）。
     */
    public void updateReasoning(String segmentText) {
        String segment = segmentText != null ? segmentText : "";
        acc.setLength(0);
        acc.append(prefix).append(segment);
        text.setText(acc.toString());
        if (onContentChanged != null) {
            onContentChanged.accept(acc.toString());
        }
    }

    /**
     * 冻结当前内容为已合并前段，后续 {@link #updateReasoning(String)} 更新新段内容，
     * 段间以空行分隔（同一轮次内跨工具调用的多段思考合并到同一子块）；
     * 同时恢复流式文本视图（Markdown 定格渲染后 body children 已被替换）。
     */
    public void beginNewSegment() {
        prefix = acc + "\n\n";
        if (body.getChildren().isEmpty() || body.getChildren().get(0) != streamingFlow) {
            body.getChildren().setAll(streamingFlow);
            notifyContentChanged();
        }
    }

    /** 段落定格：将累积内容渲染为 Markdown（样式由容器 fold-body 后代选择器统一）。 */
    public void finalizeSegment() {
        renderMarkdownBody();
        notifyContentChanged();
    }

    /** 段落定格：把累积内容渲染为 Markdown（灰卡内逐块替换）。 */
    private void renderMarkdownBody() {
        String content = acc.toString();
        if (content.isBlank()) {
            return;
        }
        try {
            VBox rendered = MarkdownFxRenderer.render(content);
            body.getChildren().clear();
            for (Node child : new ArrayList<>(rendered.getChildren())) {
                if (child instanceof Region region) {
                    region.setMaxWidth(Double.MAX_VALUE);
                }
                body.getChildren().add(child);
            }
        } catch (Exception e) {
            // 渲染失败保留流式纯文本视图
            body.getChildren().setAll(streamingFlow);
        }
    }

    @Override
    public MessageType getMessageType() {
        // 助手侧内容，左对齐
        return MessageType.ASSISTANT;
    }

    @Override
    public String getContent() {
        return acc.toString();
    }

    private void notifyContentChanged() {
        if (onContentChanged != null) {
            onContentChanged.accept("");
        }
    }
}
