package cn.bitloom.node.message;

import cn.bitloom.util.MarkdownFxRenderer;
import javafx.animation.PauseTransition;
import javafx.scene.Node;
import javafx.util.Duration;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import lombok.Setter;
import org.springframework.ai.chat.messages.MessageType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 思考内容子块：嵌入 {@link ReasoningProcessCard} 折叠容器内的一段思考正文，
 * 不再作为独立列表项、无自身折叠头（展开/折叠由外层容器统一管理）。
 * <p>
 * 流式期间块级增量 Markdown 渲染（与 {@link AssistantMessageCard} 同方案，共用边界扫描器）：
 * fence 感知按块边界切「定稿前缀 + 活跃尾块」，定稿块只渲染一次、尾块节流重渲染；
 * 段落定格（{@link #finalizeSegment()}）时整体重渲染兜底修正近似语义。
 * 同一轮次内跨工具调用的多段思考合并到容器内相邻子块（{@link #beginNewSegment()}），
 * 既有渲染节点对新累计内容仍然有效，无需重建。
 */
public class ReasoningCard extends MessageCard {

    private static final String FONT_FAMILY = "\"SF Pro Text\", -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif";

    /** 流式 Markdown 最小渲染间隔（ms）：尾块重渲染节流，约 12fps */
    private static final long MD_FLUSH_INTERVAL_MS = 80;

    private final StringBuilder acc = new StringBuilder();
    /** 思考正文容器：承载块级增量渲染的 Markdown 块（定稿块 + 尾块），样式由容器 fold-body 后代选择器统一 */
    private final VBox body;

    // 流式块级增量渲染状态（仅 FX 线程访问）
    private final MarkdownBlockBoundaryScanner boundaryScanner = new MarkdownBlockBoundaryScanner();
    /** 已定稿渲染到的累计内容偏移 */
    private int mdSettledUpto = 0;
    /** 活跃尾块节点（[mdSettledUpto, end) 的渲染结果），每次 flush 先移除再重建 */
    private final List<Node> tailNodes = new ArrayList<>();
    private boolean mdFlushPending = false;
    private long lastMdFlushAt = 0;

    /** 已合并的前段思考内容（同轮多段合并时冻结），显示文本 = prefix + 当前段 */
    private String prefix = "";

    /** 内容变化回调（由外层容器注入，转发触发滚动 / Flowless 布局刷新）。 */
    @Setter
    private Consumer<String> onContentChanged;

    /** 段定格回调（由所属二级节点注入：流式输出结束后折叠该节点）。 */
    @Setter
    private Runnable onSegmentFinalized;

    public ReasoningCard() {
        this.getStyleClass().add("reasoning-segment");

        body = new VBox(4);
        body.setMaxWidth(Double.MAX_VALUE);

        this.setMaxWidth(Double.MAX_VALUE);
        this.getChildren().add(body);
    }

    /**
     * 更新当前段思考文本（覆盖语义：每 chunk 携带本段从头到当前的完整文本）。
     */
    public void updateReasoning(String segmentText) {
        String segment = segmentText != null ? segmentText : "";
        int prevLen = acc.length();
        acc.setLength(0);
        acc.append(prefix).append(segment);
        if (acc.length() < prevLen) {
            // 覆盖语义兜底：文本变短说明非纯追加，增量状态失效，从头重渲染
            resetStreamingRenderState();
        }
        scheduleFlush();
        if (onContentChanged != null) {
            onContentChanged.accept(acc.toString());
        }
    }

    /**
     * 冻结当前内容为已合并前段，后续 {@link #updateReasoning(String)} 更新新段内容，
     * 段间以空行分隔（同一轮次内跨工具调用的多段思考合并到同一子块）。
     * 流式渲染节点始终是 Markdown 块，前段内容 + 段间空行对新累计文本仍然有效，
     * 无需重建视图，段间边界由下一次 flush 的扫描自然定稿。
     */
    public void beginNewSegment() {
        prefix = acc + "\n\n";
        notifyContentChanged();
    }

    /** 段落定格：整体重渲染为 Markdown（修正流式期间近似语义），增量状态推进到全文以续接后续段落；随后通知父节点折叠（输出完毕）。 */
    public void finalizeSegment() {
        renderMarkdownBody();
        notifyContentChanged();
        if (onSegmentFinalized != null) {
            onSegmentFinalized.run();
        }
    }

    /** 整体重渲染（灰卡内逐块替换），完成后增量状态与既有节点对齐。 */
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
            // 定格内容全部视为定稿，扫描器推进到末尾（前缀续写场景直接续接）
            mdSettledUpto = content.length();
            boundaryScanner.reset();
            boundaryScanner.advance(content, mdSettledUpto);
            tailNodes.clear();
        } catch (Exception e) {
            // 渲染失败回退纯文本视图
            body.getChildren().clear();
            TextFlow textFlow = new TextFlow();
            textFlow.getStyleClass().add("md-paragraph");
            Text text = new Text(content);
            text.getStyleClass().add("thinking-text");
            text.setFont(Font.font(FONT_FAMILY, FontPosture.ITALIC, 14));
            textFlow.getChildren().add(text);
            body.getChildren().add(textFlow);
        }
    }

    private void resetStreamingRenderState() {
        boundaryScanner.reset();
        mdSettledUpto = 0;
        tailNodes.clear();
        body.getChildren().clear();
    }

    /** 节流调度：保证两次渲染至少间隔 {@link #MD_FLUSH_INTERVAL_MS}，短时间内的多次 chunk 合并为一次渲染。 */
    private void scheduleFlush() {
        if (mdFlushPending) return;
        mdFlushPending = true;
        long delay = Math.max(0, MD_FLUSH_INTERVAL_MS - (System.currentTimeMillis() - lastMdFlushAt));
        PauseTransition pause = new PauseTransition(Duration.millis(delay));
        pause.setOnFinished(e -> {
            mdFlushPending = false;
            flushStreamingMarkdown();
        });
        pause.play();
    }

    private void flushStreamingMarkdown() {
        lastMdFlushAt = System.currentTimeMillis();
        String full = acc.toString();
        int boundary = boundaryScanner.advance(full, mdSettledUpto);

        body.getChildren().removeAll(tailNodes);
        tailNodes.clear();

        // 定稿区推进：新定稿切片只渲染一次，节点此后保持不变（布局与 GC 均稳定）
        if (boundary > mdSettledUpto) {
            addRenderedBlocks(full.substring(mdSettledUpto, boundary));
            mdSettledUpto = boundary;
        }

        // 活跃尾块重渲染（当前段落 / 未闭合代码块等，体量小）
        if (mdSettledUpto < full.length()) {
            String tail = full.substring(mdSettledUpto);
            if (!tail.isBlank()) {
                VBox rendered = MarkdownFxRenderer.render(tail);
                for (Node child : new ArrayList<>(rendered.getChildren())) {
                    applyBlockStyle(child);
                    body.getChildren().add(child);
                    tailNodes.add(child);
                }
            }
        }
    }

    /** 渲染切片并把块节点加入容器（流式定稿块与定格整体渲染共用）。 */
    private void addRenderedBlocks(String slice) {
        VBox rendered = MarkdownFxRenderer.render(slice);
        for (Node child : new ArrayList<>(rendered.getChildren())) {
            applyBlockStyle(child);
            body.getChildren().add(child);
        }
    }

    /** 块样式：撑满宽度（字号/颜色由 .process-section--thinking 的后代选择器统一）。 */
    private void applyBlockStyle(Node child) {
        if (child instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
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
