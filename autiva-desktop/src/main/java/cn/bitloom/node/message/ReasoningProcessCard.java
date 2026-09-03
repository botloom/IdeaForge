package cn.bitloom.node.message;

import cn.bitloom.node.ChevronNode;
import cn.bitloom.node.tool.ToolCallCard;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import lombok.Setter;
import cn.bitloom.harness.llm.Role;

import java.util.function.Consumer;

/**
 * 「思考过程」折叠容器（独立列表项）：把连续的思考与思考引发的工具调用
 * 收进同一折叠层，避免思考 / 工具组卡片交错产生的碎片化列表项。
 * <p>
 * 容器 body 内按时序交替挂思考二级节点（{@link ProcessSectionNode}，灰卡片收纳思考文字）
 * 与工具明细卡（{@link ToolCallCard} 平铺模式：无折叠标题行，分组明细直接展示，
 * 避免「思考过程折叠 &gt; 工具折叠」嵌套）。流式期间由 ViewModel 调
 * {@link #expand()} 展开，正文开始或轮次结束时 {@link #collapse()} 折叠。
 * <p>
 * 子块内容高度变化经 onContentChanged 逐级转发给 Controller
 * （滚动 / Flowless 布局刷新），子块本身不进入虚拟列表、不经 buildMessageRow。
 */
public class ReasoningProcessCard extends MessageCard {

    private final VBox body;
    private final VBox section;
    private final ChevronNode chevron;
    private boolean expanded = false;

    /** 内容变化回调（供 Controller 触发滚动到底部 / Flowless 布局刷新）。 */
    @Setter
    private Consumer<String> onContentChanged;

    public ReasoningProcessCard() {
        this.getStyleClass().add("reasoning-card");
        this.getStyleClass().add("reasoning-process-card");

        Label title = new Label("思考过程");
        title.getStyleClass().add("fold-title");

        chevron = new ChevronNode();
        chevron.setExpanded(expanded);

        HBox header = new HBox(6);
        header.getStyleClass().add("fold-header");
        header.setAlignment(Pos.CENTER_LEFT);
        header.getChildren().addAll(title, chevron);
        header.setOnMouseClicked(e -> toggle());

        body = new VBox(8);
        body.getStyleClass().add("fold-body");
        body.setMaxWidth(Double.MAX_VALUE);

        section = new VBox(4);
        section.getStyleClass().add("fold-section");
        // 初始折叠：body 不挂入 section（折叠即真正卸载节点，滚动时不参与 scene/layout/CSS）
        section.getChildren().add(header);
        section.setMaxWidth(Double.MAX_VALUE);

        this.getStyleClass().add("chat-message");
        this.getChildren().add(section);
    }

    /** 展开（流式输出期间由 ViewModel 调用）。 */
    public void expand() {
        setExpanded(true);
    }

    /** 折叠（正文开始 / 轮次结束时由 ViewModel 调用），折叠前通知布局刷新。 */
    public void collapse() {
        setExpanded(false);
        notifyContentChanged();
    }

    private void toggle() {
        setExpanded(!expanded);
        notifyContentChanged();
    }

    private void setExpanded(boolean value) {
        expanded = value;
        // 折叠时真正从 section 移除 body（节点离开 scene，滚动不再参与挂载/CSS/layout），
        // 展开时加回。相比 setVisible/setManaged 只隐藏不卸载，能显著降低折叠态滚动开销。
        if (value) {
            if (!section.getChildren().contains(body)) {
                section.getChildren().add(body);
            }
        } else {
            section.getChildren().remove(body);
        }
        chevron.setExpanded(value);
    }

    /**
     * 获取「思考」二级节点：容器尾部二级节点是思考 → 复用（连续思考合并）；
     * 否则新建。
     */
    public ProcessSectionNode thinkingSection() {
        return sectionFor(ProcessSectionNode.Kind.THINKING);
    }

    private ProcessSectionNode sectionFor(ProcessSectionNode.Kind kind) {
        if (!body.getChildren().isEmpty()
                && body.getChildren().get(body.getChildren().size() - 1) instanceof ProcessSectionNode tail
                && tail.getKind() == kind) {
            return tail;
        }
        ProcessSectionNode node = new ProcessSectionNode(kind);
        node.setOnContentChanged(c -> notifyContentChanged());
        body.getChildren().add(node);
        notifyContentChanged();
        return node;
    }

    /**
     * 加入工具组卡：直接挂在 body（无「工具调用」二级节点标题），注入回调向上转发。
     */
    public void addToolCard(ToolCallCard card) {
        card.setOnContentChanged(c -> notifyContentChanged());
        body.getChildren().add(card);
        notifyContentChanged();
    }

    /** 容器最后一个工具组卡（相邻工具组合并判断用），非工具卡返回 null。 */
    public ToolCallCard lastToolCard() {
        if (!body.getChildren().isEmpty()
                && body.getChildren().get(body.getChildren().size() - 1) instanceof ToolCallCard existing) {
            return existing;
        }
        return null;
    }

    @Override
    public Role getMessageType() {
        // 助手侧内容，左对齐
        return Role.ASSISTANT;
    }

    @Override
    public String getContent() {
        StringBuilder sb = new StringBuilder();
        for (Node node : body.getChildren()) {
            if (!(node instanceof ProcessSectionNode section)
                    || section.getKind() != ProcessSectionNode.Kind.THINKING) {
                continue;
            }
            // 思考节点：收集其下全部思考子块内容（多段合并的容器级思考全文）
            for (Node child : section.getContentChildren()) {
                if (child instanceof ReasoningCard rc) {
                    if (sb.length() > 0) {
                        sb.append("\n\n");
                    }
                    sb.append(rc.getContent());
                }
            }
        }
        return sb.toString();
    }

    private void notifyContentChanged() {
        if (onContentChanged != null) {
            onContentChanged.accept("");
        }
    }
}
