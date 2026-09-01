package cn.bitloom.node.message;

import cn.bitloom.node.svg.SvgImageView;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.DataFormat;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import lombok.Getter;
import org.springframework.ai.chat.messages.MessageType;

import java.util.Map;
import java.util.function.Consumer;

@Getter
public class UserMessageCard extends MessageCard {

    private static final String REVERT_ICON = "/cn/bitloom/images/message-revert.svg";
    private static final String COPY_ICON = "/cn/bitloom/images/message-copy.svg";

    private final String content;

    /** 所属用户消息事件 ID（撤回定位键）；null = 未关联，撤回按钮隐藏 */
    @Getter
    private String revertEventId;
    private Consumer<String> revertHandler;
    private final Button revertBtn;
    private final Button copyBtn;

    public UserMessageCard(String content) {
        this(content, null, null);
    }

    /**
     * @param revertEventId 用户消息事件 ID（历史加载直接携带；实时回显卡由 sendMessage 经 bindRevert 回填）
     * @param revertHandler 撤回回调（入参为事件 ID）
     */
    public UserMessageCard(String content, String revertEventId, Consumer<String> revertHandler) {
        this.content = content != null ? content.trim() : "";
        this.revertEventId = revertEventId;
        this.revertHandler = revertHandler;

        // 外层卡片（无背景）承载 hover 检测：气泡与下方按钮行同属 hover 区域，
        // 鼠标从气泡移向按钮行不会中断按钮显示
        this.getStyleClass().add("user-message-card");

        TextFlow textFlow = new TextFlow();
        textFlow.getStyleClass().add("chat-message__content");
        Text text = new Text(this.content);
        text.setFont(Font.font("\"SF Pro Text\", -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif", 15));
        textFlow.getChildren().add(text);

        // 气泡容器：背景/圆角/阴影样式，仅包消息内容
        VBox bubble = new VBox(textFlow);
        bubble.getStyleClass().addAll("chat-message", "chat-message--user");

        // 操作按钮行位于气泡外部下方，右对齐，hover 消息卡时显现
        copyBtn = buildIconButton(COPY_ICON, "复制", this::copyToClipboard);
        revertBtn = buildIconButton(REVERT_ICON, "撤回此消息及之后的修改", this::onRevert);
        HBox actions = new HBox(4, copyBtn, revertBtn);
        actions.setAlignment(Pos.CENTER_RIGHT);
        this.getChildren().addAll(bubble, actions);

        updateRevertVisibility();
    }

    /** 回填撤回定位（实时回显卡在 sendMessage 时关联事件 ID），关联后撤回按钮可用 */
    public void bindRevert(String eventId, Consumer<String> handler) {
        this.revertEventId = eventId;
        this.revertHandler = handler;
        updateRevertVisibility();
    }

    private Button buildIconButton(String iconPath, String tooltip, Runnable action) {
        Button btn = new Button();
        btn.getStyleClass().add("message-action-btn");
        SvgImageView icon = new SvgImageView(iconPath);
        icon.setStrokeColor("#86868b");
        icon.setFitWidth(13);
        icon.setFitHeight(13);
        btn.setGraphic(icon);
        btn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        btn.setTooltip(new Tooltip(tooltip));
        btn.setOnAction(_ -> action.run());
        return btn;
    }

    private void copyToClipboard() {
        Clipboard.getSystemClipboard().setContent(Map.of(DataFormat.PLAIN_TEXT, content));
    }

    private void onRevert() {
        if (revertHandler != null && revertEventId != null) {
            revertHandler.accept(revertEventId);
        }
    }

    private void updateRevertVisibility() {
        boolean revertible = revertEventId != null;
        revertBtn.setManaged(revertible);
        revertBtn.setVisible(revertible);
    }

    @Override
    public MessageType getMessageType() {
        return MessageType.USER;
    }
}
