package cn.bitloom.node.message;

import cn.bitloom.node.ChevronNode;
import cn.bitloom.node.svg.SvgImageView;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.function.Consumer;

/**
 * 「思考过程」容器内的二级折叠节点（思考）：自带小标题 + chevron，
 * 可独立折叠（默认折叠：容器打开时只显示标题行，点击展开内容）；
 * 思考子块挂在其 content 下（灰卡片收纳思考文字）。
 * <p>
 * 子块内容变化经 onContentChanged 向上转发给容器（容器再转发 Controller）。
 */
public class ProcessSectionNode extends VBox {

    /** 二级节点类型：标题文案由类型决定 */
    public enum Kind {
        THINKING("思考");

        private final String title;

        Kind(String title) {
            this.title = title;
        }
    }

    @Getter
    private final Kind kind;
    private final VBox content = new VBox(6);
    private final ChevronNode chevron;
    /** 默认折叠：容器打开时只显示「思考」标题行，点击展开内容 */
    private boolean expanded = false;

    /** 内容变化回调（由容器注入并向上转发）。 */
    @Setter
    private Consumer<String> onContentChanged;

    public ProcessSectionNode(Kind kind) {
        this.kind = kind;
        this.getStyleClass().add("process-section");
        this.getStyleClass().add("process-section--thinking");
        this.setMaxWidth(Double.MAX_VALUE);
        this.setSpacing(4);

        Label title = new Label(kind.title);
        title.getStyleClass().add("process-section__title");

        SvgImageView icon = new SvgImageView();
        icon.setFitWidth(14);
        icon.setFitHeight(14);
        icon.setSvgPath("/cn/bitloom/images/think.svg");
        icon.setStrokeColor("#86868b");

        chevron = new ChevronNode();
        chevron.setExpanded(expanded);

        HBox header = new HBox(4);
        header.getStyleClass().add("process-section__header");
        header.setAlignment(Pos.CENTER_LEFT);
        header.getChildren().addAll(icon, title, chevron);
        header.setOnMouseClicked(e -> toggle());

        content.getStyleClass().add("process-section__content");
        content.setMaxWidth(Double.MAX_VALUE);

        // content 按初始折叠状态挂载：默认折叠时不加入（真正卸载，滚动零开销），
        // 否则改默认折叠后灰卡内容仍会显示
        this.getChildren().add(header);
        if (expanded) {
            this.getChildren().add(content);
        }
    }

    /**
     * 加入思考子块，并注入内容变化回调向上转发；
     * 段定格回调：思考流式输出结束（finalizeSegment）时折叠本节点。
     */
    public void addContent(ReasoningCard rc) {
        rc.setOnContentChanged(c -> notifyContentChanged());
        rc.setOnSegmentFinalized(this::collapse);
        content.getChildren().add(rc);
        notifyContentChanged();
    }

    /** 本节点最后一个内容子块（相邻同类合并判断用），空返回 null。 */
    public Node lastContent() {
        return content.getChildren().isEmpty() ? null
                : content.getChildren().get(content.getChildren().size() - 1);
    }

    /** 本节点全部内容子块（容器收集思考全文用）。 */
    public List<Node> getContentChildren() {
        return content.getChildren();
    }

    /** 展开本节点内容（思考段开始流式时由 ViewModel 调用）。 */
    public void expand() {
        setExpanded(true);
    }

    /** 折叠本节点内容（思考段定格时由子块通知触发）。 */
    public void collapse() {
        setExpanded(false);
    }

    private void toggle() {
        setExpanded(!expanded);
        notifyContentChanged();
    }

    /** 展开本节点内容（思考流式输出时由调用方自动展开，无需用户手动点击）。 */
    public void expandContent() {
        if (!expanded) {
            setExpanded(true);
        }
    }

    private void setExpanded(boolean value) {
        expanded = value;
        // 折叠时真正卸载 content（节点离开 scene），展开时加回，降低滚动开销
        if (value) {
            if (!getChildren().contains(content)) {
                getChildren().add(content);
            }
        } else {
            getChildren().remove(content);
        }
        chevron.setExpanded(value);
    }

    private void notifyContentChanged() {
        if (onContentChanged != null) {
            onContentChanged.accept("");
        }
    }
}
