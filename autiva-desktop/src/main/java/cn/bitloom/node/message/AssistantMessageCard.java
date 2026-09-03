package cn.bitloom.node.message;

import cn.bitloom.util.MarkdownFxRenderer;
import cn.bitloom.harness.llm.Role;
import javafx.animation.PauseTransition;
import javafx.beans.property.*;
import javafx.scene.Node;
import javafx.util.Duration;
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
 * 流式期间做块级增量 Markdown 渲染（边输出边转 md）：fence 感知地按块边界把累计内容切成
 * 「已定稿前缀 + 活跃尾块」——定稿块只渲染一次、节点保持不变，尾块每次 flush 重渲染（体量很小），
 * 单次渲染成本 O(新增内容) 而非 O(全文)。complete() 时整体重渲染一次，
 * 修正流式期间的近似语义（松散列表、引用定义、setext 标题等）。
 * 思考与工具调用由独立卡片承载（ReasoningCard / ToolCallCard），各自直接加入虚拟列表按事件顺序展示。
 */
@Getter
@Slf4j
public class AssistantMessageCard extends MessageCard {

    private static final String FONT_FAMILY = "\"SF Pro Text\", -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif";

    /** 流式 Markdown 最小渲染间隔（ms）：尾块重渲染节流，约 12fps，肉眼流畅且开销有界 */
    private static final long MD_FLUSH_INTERVAL_MS = 80;

    private final StringProperty content = new SimpleStringProperty("");
    private final ObjectProperty<String> finishReason = new SimpleObjectProperty<>(null);
    private final BooleanProperty streaming = new SimpleBooleanProperty(false);

    private final StringBuilder accumulator = new StringBuilder();
    private boolean isStreamingActive = false;

    // 流式块级增量渲染状态（仅 FX 线程访问）
    /** Fence 感知的块边界扫描（与 {@link ReasoningCard} 共用实现） */
    private final MarkdownBlockBoundaryScanner boundaryScanner = new MarkdownBlockBoundaryScanner();
    /** 已定稿渲染到的累计内容偏移 */
    private int mdSettledUpto = 0;
    /** 活跃尾块节点（[mdSettledUpto, end) 的渲染结果），每次 flush 先移除再重建 */
    private final List<Node> tailNodes = new ArrayList<>();
    private boolean mdFlushPending = false;
    private long lastMdFlushAt = 0;

    @Setter
    private Consumer<String> onContentChanged;

    public AssistantMessageCard() {
        this.getStyleClass().add("chat-message");
        this.getStyleClass().add("chat-message--assistant");

        // 监听 streamingProperty，流式结束时触发整体 Markdown 重渲染（修正近似语义）
        streaming.addListener((obs, oldVal, newVal) -> {
            if (!newVal && getContent() != null) {
                renderMarkdown(getContent());
                if (onContentChanged != null) {
                    onContentChanged.accept(getContent());
                }
            }
        });
    }

    @Override
    public Role getMessageType() {
        return Role.ASSISTANT;
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
     * 累积流式内容。自动设置 streaming=true，调度节流式增量 Markdown 渲染。
     */
    public void appendContent(String chunk) {
        if (!isStreamingActive) {
            isStreamingActive = true;
            streaming.set(true);
            if (!getStyleClass().contains("chat-message--streaming")) {
                getStyleClass().add("chat-message--streaming");
            }
        }
        accumulator.append(chunk != null ? chunk : "");
        scheduleFlush();
    }

    /**
     * 结束流式输出。取消 pending flush，设置 content，触发整体 Markdown 重渲染。
     * 累积内容为空时 content 置 null（供外部判断是否移除空卡）。
     */
    public void complete(String reason) {
        mdFlushPending = false;
        isStreamingActive = false;
        content.set(accumulator.isEmpty() ? null : accumulator.toString());
        finishReason.set(reason);
        streaming.set(false); // 触发 streaming listener → renderMarkdown
    }

    /** 判断正文是否为空（供外部决定是否移除空卡）。 */
    public boolean isEmpty() {
        String c = content.get();
        return c == null || c.isBlank();
    }

    /**
     * 重开已完成卡片继续流式（相邻正文卡片合并场景）：
     * 累计内容从原 content 续接（两段文本间以空行分隔），清空渲染状态后
     * 下一帧 flush 会把既有内容整体渲染为定稿块，后续 chunk 继续增量追加。
     */
    public void reopen() {
        String previous = getContent() == null ? "" : getContent();
        accumulator.setLength(0);
        accumulator.append(previous);
        if (!previous.isBlank()) {
            accumulator.append("\n\n");
        }
        finishReason.set(null);
        resetStreamingRenderState();
        isStreamingActive = true;
        streaming.set(true);
        if (!getStyleClass().contains("chat-message--streaming")) {
            getStyleClass().add("chat-message--streaming");
        }
        lastMdFlushAt = 0;
        scheduleFlush();
    }

    private void resetStreamingRenderState() {
        boundaryScanner.reset();
        mdSettledUpto = 0;
        tailNodes.clear();
        getChildren().clear();
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
        if (!isStreamingActive) return;
        lastMdFlushAt = System.currentTimeMillis();
        String full = accumulator.toString();
        int boundary = boundaryScanner.advance(full, mdSettledUpto);

        getChildren().removeAll(tailNodes);
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
                    getChildren().add(child);
                    tailNodes.add(child);
                }
            }
        }

        if (onContentChanged != null) {
            onContentChanged.accept(full);
        }
    }

    /** 渲染切片并把块节点加入卡片（流式定稿块与完成态整体渲染共用）。 */
    private void addRenderedBlocks(String slice) {
        VBox rendered = MarkdownFxRenderer.render(slice);
        for (Node child : new ArrayList<>(rendered.getChildren())) {
            applyBlockStyle(child);
            getChildren().add(child);
        }
    }

    /** 块样式：撑满宽度 + 内容样式类（配合 .chat-message--streaming 的流式透明度）。 */
    private void applyBlockStyle(Node child) {
        if (child instanceof TextFlow tf) {
            tf.setMaxWidth(Double.MAX_VALUE);
            tf.getStyleClass().add("chat-message__content");
        } else if (child instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
    }

    private void renderMarkdown(String content) {
        getChildren().clear();
        getStyleClass().remove("chat-message--streaming");
        resetStreamingRenderState();

        if (content == null || content.isBlank()) {
            return;
        }

        try {
            addRenderedBlocks(content);
        } catch (Exception e) {
            log.error("Markdown渲染失败，使用TextFlow回退", e);
            TextFlow textFlow = new TextFlow();
            textFlow.getStyleClass().add("chat-message__content");
            Text text = new Text(content);
            text.setFont(Font.font(FONT_FAMILY, 15));
            textFlow.getChildren().add(text);
            getChildren().add(textFlow);
        }
    }
}
