package cn.bitloom.node.message;

import cn.bitloom.node.ChevronNode;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.springframework.ai.chat.messages.MessageType;

/**
 * 思考折叠块卡片：「思考过程」标题 + 展开/收起按钮，默认折叠。
 * <p>
 * 独立 MessageCard 直接加入虚拟列表；同一轮对话内的多次思考流（跨工具调用轮次）
 * 复用同一张卡更新内容（覆盖语义），即同类消息合并展示。
 */
public class ReasoningCard extends MessageCard {

    private static final String FONT_FAMILY = "\"SF Pro Text\", -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif";

    private final StringBuilder acc = new StringBuilder();
    private final ChevronNode chevron;
    private final TextFlow body;
    private final Text text;
    private boolean expanded = false;
    /** 已合并的前段思考内容（分段合并时冻结），显示文本 = prefix + 当前段 */
    private String prefix = "";

    public ReasoningCard() {
        this.getStyleClass().add("reasoning-card");

        Label title = new Label("思考过程");
        title.getStyleClass().add("fold-title");

        chevron = new ChevronNode();
        chevron.setExpanded(expanded);

        HBox header = new HBox(4);
        header.getStyleClass().add("fold-header");
        header.setAlignment(Pos.CENTER_LEFT);
        header.getChildren().addAll(title, chevron);
        header.setOnMouseClicked(e -> toggle());

        body = new TextFlow();
        body.getStyleClass().add("fold-body");
        body.setMaxWidth(Double.MAX_VALUE);
        body.setVisible(expanded);
        body.setManaged(expanded);

        text = new Text();
        text.getStyleClass().add("thinking-text");
        text.setFont(Font.font(FONT_FAMILY, FontPosture.ITALIC, 15));
        body.getChildren().add(text);

        VBox section = new VBox(6);
        section.getStyleClass().add("fold-section");
        section.getChildren().addAll(header, body);
        section.setMaxWidth(Double.MAX_VALUE);

        this.getStyleClass().add("chat-message");
        this.getChildren().add(section);
    }

    /**
     * 更新当前段思考文本（覆盖语义：每 chunk 携带本段从头到当前的完整文本）。
     * 若之前已合并过前段（{@link #beginNewSegment()}），前段内容保持不变。
     */
    public void updateReasoning(String segmentText) {
        String segment = segmentText != null ? segmentText : "";
        acc.setLength(0);
        acc.append(prefix).append(segment);
        text.setText(acc.toString());
    }

    /**
     * 冻结当前内容为已合并前段，后续 {@link #updateReasoning(String)} 更新新段内容，
     * 段间以空行分隔（相邻同类卡片合并场景）。
     */
    public void beginNewSegment() {
        prefix = acc + "\n\n";
    }

    private void toggle() {
        expanded = !expanded;
        body.setVisible(expanded);
        body.setManaged(expanded);
        chevron.setExpanded(expanded);
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
}
