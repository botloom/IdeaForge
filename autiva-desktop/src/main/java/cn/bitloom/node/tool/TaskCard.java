package cn.bitloom.node.tool;

import cn.bitloom.agentic.event.MessageEvent;
import cn.bitloom.agentic.event.UICardEvent;
import cn.bitloom.node.ChevronNode;
import cn.bitloom.node.message.AssistantMessageCard;
import cn.bitloom.node.message.NotificationCard;
import cn.bitloom.node.message.ProcessSectionNode;
import cn.bitloom.node.message.ReasoningCard;
import cn.bitloom.node.message.ReasoningProcessCard;
import cn.bitloom.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 子智能体折叠层：外壳为「状态行 + 可折叠正文」，正文内以与主对话一致的组件
 * 渲染子智能体输出（思考过程容器 / AI 正文卡 / 工具调用组卡 / Todo / 问答 / 审批），
 * 渲染状态机与主链路（AbstractHomePageViewModel 的 assistant 状态机）同构。
 * 子智能体分支事件（MessageEvent / UICardEvent）由 ToolUIBridge 路由到此。
 */
public class TaskCard extends VBox {

    private final VBox body;
    private final VBox messagesBox;
    private final ChevronNode chevron;

    /** 项目根目录（ToolCallCard 将相对 filePath 解析为可点击链接用） */
    private final String projectPath;

    // ===== 渲染状态机（与主链路同构，目标容器为 messagesBox） =====
    private ReasoningProcessCard currentReasoningProcess;
    private ReasoningCard currentReasoningCard;
    private AssistantMessageCard currentAssistantCard;
    private ToolCallCard currentToolGroup;
    private boolean needNewToolGroup = true;
    private final Set<String> activeToolCallIds = new HashSet<>();

    private boolean userCollapsed = false;

    @Setter
    private Consumer<String> onContentChanged;

    public TaskCard(String taskJson) {
        this.getStyleClass().add("chat-message");
        this.getStyleClass().add("chat-message--task");

        JsonNode task = parseTask(taskJson);
        this.projectPath = getString(task, "projectPath");

        // 折叠头与「思考过程」容器（ReasoningProcessCard）样式一致：fold-title + chevron
        HBox header = new HBox(6);
        header.getStyleClass().add("fold-header");
        header.setAlignment(Pos.CENTER_LEFT);

        chevron = new ChevronNode();
        chevron.setExpanded(true);

        Label title = new Label(getSubagentDisplayName(getString(task, "subagentName")) + " Agent");
        title.getStyleClass().add("fold-title");

        header.getChildren().addAll(title, chevron);
        header.setOnMouseClicked(e -> toggleBody());
        this.getChildren().add(header);

        body = new VBox(8);
        body.getStyleClass().add("chat-message__task-body");
        body.setVisible(false);
        body.setManaged(false);

        messagesBox = new VBox(4);
        messagesBox.getStyleClass().add("chat-message__task-messages");
        body.getChildren().add(messagesBox);

        this.getChildren().add(body);

        header.setOnMouseClicked(e -> toggleBody());
    }

    // ===== 事件入口（由 ToolUIBridge 路由） =====

    /**
     * 处理子智能体 assistant 消息事件：与主链路 assistant 渲染状态机同构 ——
     * chunk 累积（思考 → ReasoningCard、正文 → AssistantMessageCard），
     * TOOL_CALLS 闭合正文卡，STOP 等收尾并折叠思考容器与工具组。
     */
    public void processEvent(MessageEvent e) {
        if (!e.isAssistantMessage()) {
            return;
        }
        String finishReason = e.getFinishReason();
        String text = e.getText();
        String reasoning = e.getReasoningContent();
        boolean isChunk = finishReason == null || finishReason.isBlank() || "_UNKNOWN".equals(finishReason);

        if (isChunk) {
            // 思考流：正文流开启后到达的 reasoning 忽略（正文 chunk 会残留携带完整 reasoningContent）
            if (reasoning != null && !reasoning.isBlank() && currentAssistantCard == null) {
                if (currentReasoningCard == null) {
                    currentReasoningCard = obtainReasoningCard();
                }
                currentReasoningCard.updateReasoning(reasoning);
            }
            if (currentAssistantCard == null) {
                // 空文本 chunk（工具间 silent revision）不构成新的 AI 话语，不新建卡片
                if (text == null || text.isBlank()) {
                    ensureBodyVisible();
                    notifyContentChanged();
                    return;
                }
                // 正文开始：思考段定格、思考容器折叠，此后工具调用归属新的一组
                currentAssistantCard = obtainAssistantCard();
                if (currentReasoningCard != null) {
                    currentReasoningCard.finalizeSegment();
                    currentReasoningCard = null;
                }
                collapseReasoningProcess();
                needNewToolGroup = true;
            }
            if (text != null && !text.isBlank()) {
                currentAssistantCard.appendContent(text);
            }
        } else if ("TOOL_CALLS".equals(finishReason)) {
            // 工具调用即将发生：闭合当前正文卡，思考容器保持开启，随后的工具组收进同一折叠层
            finishStreamingText();
        } else {
            // 收尾：STOP / LENGTH 等结束原因
            finishStreamingText();
            collapseReasoningProcess();
            if (currentToolGroup != null) {
                currentToolGroup.collapseNow();
                currentToolGroup = null;
            }
            needNewToolGroup = true;
            if (currentAssistantCard != null) {
                currentAssistantCard.complete(finishReason != null ? finishReason : "STOP");
                if (currentAssistantCard.isEmpty()) {
                    messagesBox.getChildren().remove(currentAssistantCard);
                }
                currentAssistantCard = null;
            }
        }
        ensureBodyVisible();
        notifyContentChanged();
    }

    /**
     * 处理子智能体工具卡片事件（UICardEvent TOOL_CARD，由 ToolCardEventHook 发布）：
     * CREATED 闭合正文卡并把调用加入工具组（同一轮话语的连续工具聚合为一组），
     * COMPLETED/FAILED 在组内调用全部结束时折叠。
     */
    public void processUICardEvent(UICardEvent event) {
        if (event.getType() != UICardEvent.Type.TOOL_CARD) {
            return;
        }
        switch (event.getStatus()) {
            case CREATED -> handleToolCallCreated(event.getCardId(), event.getToolName(), event.getCardJson());
            case COMPLETED, FAILED -> handleToolCallFinished(event.getCardId(), event.getToolName());
            default -> { }
        }
        ensureBodyVisible();
        notifyContentChanged();
    }

    private void handleToolCallCreated(String callId, String toolName, String arguments) {
        // 任何工具执行前先闭合当前流式正文/思考，工具结束后的新 AI 文本新起一个冒泡
        finishStreamingText();
        if ("Task".equals(toolName) || "TodoWrite".equals(toolName)) {
            // 独立渲染（TodoCard / 嵌套 TaskCard），不参与工具组
            return;
        }
        if (!isCardedTool(toolName)) {
            // 非展示工具：仅借 CREATED 事件分隔思考段，不创建工具组卡
            return;
        }
        if (callId != null) {
            activeToolCallIds.add(callId);
        }
        ToolCallCard group = currentToolGroup;
        if (group == null || needNewToolGroup) {
            group = obtainToolGroup();
            group.expandNow();
        }
        group.addToolCall(toolName, arguments);
        group.markRunning();
    }

    private void handleToolCallFinished(String callId, String toolName) {
        if (!isCardedTool(toolName)) {
            return;
        }
        if (callId != null) {
            activeToolCallIds.remove(callId);
        }
        if (currentToolGroup != null && activeToolCallIds.isEmpty()) {
            currentToolGroup.collapseNow();
        }
    }

    /** 展示工具组卡片的工具，与主链路判定一致。其余工具仅用于分隔思考段。 */
    private static boolean isCardedTool(String toolName) {
        return switch (toolName) {
            case "Read", "Write", "Edit", "Command" -> true;
            default -> false;
        };
    }

    // ===== 卡片获取与合并（与主链路 obtainXxx 同构，目标容器为 messagesBox） =====

    /**
     * 获取「思考过程」折叠容器：未闭合则复用；messagesBox 尾部是已闭合容器则重开复用；
     * 否则新建并展开。
     */
    private ReasoningProcessCard obtainReasoningProcess() {
        if (currentReasoningProcess != null) {
            return currentReasoningProcess;
        }
        if (peekLast() instanceof ReasoningProcessCard existing) {
            existing.expand();
            currentReasoningProcess = existing;
            return existing;
        }
        ReasoningProcessCard card = new ReasoningProcessCard();
        card.setOnContentChanged(c -> notifyContentChanged());
        addNode(card);
        card.expand();
        currentReasoningProcess = card;
        return card;
    }

    /** 折叠并闭合当前「思考过程」容器（正文开始 / 轮次结束时调用）。 */
    private void collapseReasoningProcess() {
        if (currentReasoningProcess != null) {
            currentReasoningProcess.collapse();
            currentReasoningProcess = null;
        }
    }

    /** 获取思考子块：容器尾部二级节点是思考则复用（分段追加），否则新建。思考流式期间自动展开。 */
    private ReasoningCard obtainReasoningCard() {
        ReasoningProcessCard container = obtainReasoningProcess();
        ProcessSectionNode section = container.thinkingSection();
        section.expandContent();
        if (section.lastContent() instanceof ReasoningCard existing) {
            existing.beginNewSegment();
            return existing;
        }
        ReasoningCard card = new ReasoningCard();
        section.addContent(card);
        return card;
    }

    /** 获取正文卡：messagesBox 尾部是正文卡则重开续写（reopen），否则新建。 */
    private AssistantMessageCard obtainAssistantCard() {
        if (peekLast() instanceof AssistantMessageCard existing) {
            existing.reopen();
            return existing;
        }
        AssistantMessageCard card = new AssistantMessageCard();
        card.setOnContentChanged(c -> notifyContentChanged());
        addNode(card);
        return card;
    }

    /**
     * 获取工具组卡：思考容器未闭合 → 工具组挂进容器 body；容器已闭合/不存在 →
     * messagesBox 尾部是工具组则复用，否则新建独立挂入。
     */
    private ToolCallCard obtainToolGroup() {
        if (currentReasoningProcess != null) {
            if (!needNewToolGroup) {
                ToolCallCard last = currentReasoningProcess.lastToolCard();
                if (last != null) {
                    currentToolGroup = last;
                    return last;
                }
            }
            ToolCallCard group = new ToolCallCard(projectPath);
            group.setOnContentChanged(c -> notifyContentChanged());
            currentToolGroup = group;
            needNewToolGroup = false;
            currentReasoningProcess.addToolCard(group);
            return group;
        }
        if (peekLast() instanceof ToolCallCard existing) {
            currentToolGroup = existing;
            needNewToolGroup = false;
            return existing;
        }
        ToolCallCard group = new ToolCallCard(projectPath);
        group.setOnContentChanged(c -> notifyContentChanged());
        currentToolGroup = group;
        needNewToolGroup = false;
        addNode(group);
        return group;
    }

    /** 结束当前流式正文卡（未结束则移除空卡），思考段一并定格。 */
    private void finishStreamingText() {
        if (currentReasoningCard != null) {
            currentReasoningCard.finalizeSegment();
            currentReasoningCard = null;
        }
        if (currentAssistantCard == null) {
            return;
        }
        AssistantMessageCard card = currentAssistantCard;
        currentAssistantCard = null;
        card.complete("TOOL_CALLS");
        if (card.isEmpty()) {
            messagesBox.getChildren().remove(card);
        }
    }

    /** 收尾定格：闭合思考段/容器/工具组与正文卡（完成与失败共用）。 */
    private void finalizeRendering() {
        finishStreamingText();
        collapseReasoningProcess();
        if (currentToolGroup != null) {
            currentToolGroup.collapseNow();
            currentToolGroup = null;
        }
        needNewToolGroup = true;
        if (currentAssistantCard != null) {
            currentAssistantCard.complete("STOP");
            if (currentAssistantCard.isEmpty()) {
                messagesBox.getChildren().remove(currentAssistantCard);
            }
            currentAssistantCard = null;
        }
    }

    private Node peekLast() {
        var children = messagesBox.getChildren();
        return children.isEmpty() ? null : children.get(children.size() - 1);
    }

    /** 添加节点到 messagesBox，并确保宽度跟随容器（避免内容溢出）。 */
    private void addNode(Node node) {
        if (node instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
        messagesBox.getChildren().add(node);
    }

    // ===== 外壳：Todo / 问答 / 审批挂载、折叠、状态 =====

    public void addTodoCard(TodoCard card) {
        Platform.runLater(() -> {
            addNode(card);
            ensureBodyVisible();
            notifyContentChanged();
        });
    }

    public void addQuestionCard(QuestionCard card) {
        Platform.runLater(() -> {
            addNode(card);
            ensureBodyVisible();
            notifyContentChanged();
        });
    }

    public void addApprovalCard(ApprovalCard card) {
        Platform.runLater(() -> {
            addNode(card);
            ensureBodyVisible();
            notifyContentChanged();
        });
    }

    private void ensureBodyVisible() {
        if (!body.isVisible() && !userCollapsed) {
            setBodyVisible(true);
        }
    }

    private void toggleBody() {
        boolean expanded = body.isVisible();
        if (expanded) {
            userCollapsed = true;
        }
        setBodyVisible(!expanded);
        notifyContentChanged();
    }

    private void setBodyVisible(boolean visible) {
        body.setVisible(visible);
        body.setManaged(visible);
        chevron.setExpanded(visible);
    }

    /**
     * 折叠卡片正文（仅保留 header）。完成后自动调用，用户可点击 header 重新展开查看。
     */
    private void collapseBody() {
        if (body.isVisible()) {
            setBodyVisible(false);
            notifyContentChanged();
        }
    }

    /**
     * 完成：定格收尾渲染状态机（子智能体输出已流式完整展示，工具结果不再重复渲染），
     * 自动折叠正文，点击 header 可随时展开查看。
     */
    public void complete(String result) {
        Platform.runLater(() -> {
            finalizeRendering();
            collapseBody();
        });
    }

    /** 失败：定格收尾并以系统通知样式展示错误摘要。 */
    public void fail(String error) {
        Platform.runLater(() -> {
            finalizeRendering();
            addNode(new NotificationCard("子智能体执行失败: " + (error != null ? error : "未知错误")));
            ensureBodyVisible();
            notifyContentChanged();
        });
    }

    private void notifyContentChanged() {
        if (onContentChanged != null) {
            onContentChanged.accept("");
        }
    }

    private JsonNode parseTask(String taskJson) {
        try {
            return JsonUtils.parse(taskJson);
        } catch (Exception e) {
            ObjectNode fallback = JsonUtils.createObject();
            fallback.put("description", taskJson);
            return fallback;
        }
    }

    private String getString(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value != null && !value.isNull() ? value.asText() : null;
    }

    private String getSubagentDisplayName(String subagentType) {
        if (subagentType == null) return "Task";
        return switch (subagentType.toLowerCase()) {
            case "code" -> "Code";
            case "search" -> "Search";
            case "a2a" -> "A2A";
            default -> subagentType;
        };
    }
}
