package cn.bitloom.node.canvas.tool;

import cn.bitloom.harness.tool.AbstractTool;
import cn.bitloom.harness.tool.ToolContext;
import cn.bitloom.harness.tool.ToolParam;
import cn.bitloom.harness.tool.ToolResult;
import cn.bitloom.node.canvas.model.CanvasElement;
import cn.bitloom.node.canvas.model.CanvasScene;
import cn.bitloom.node.canvas.model.FreehandElement;
import cn.bitloom.node.canvas.model.TextElement;
import cn.bitloom.util.JsonUtils;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

/**
 * 图表导出工具。
 * 将画布上的图表元素序列化为结构化描述，供智能体生成代码。
 * 使用 Builder 模式创建，不依赖 Spring 管理。
 */
@Slf4j
public class DiagramExportTool extends AbstractTool<DiagramExportTool.Input> {

    private static final String DESCRIPTION = "将画布上的图表导出为结构化JSON描述，用于生成代码。"
        + "返回包含所有元素位置、类型、文字等信息的JSON。";

    private final CanvasScene scene;

    private DiagramExportTool(CanvasScene scene) {
        super("DiagramExport", DESCRIPTION, Input.class);
        this.scene = scene;
    }

    public record Input(
        @ToolParam(description = "目标编程语言或技术：html/java/sql/python/react/vue") String targetLanguage,
        @ToolParam(description = "生成代码的用途说明，如'前端页面'或'数据库建表'") String purpose) {
    }

    @Override
    public ToolResult execute(Input input, ToolContext context) {
        String targetLanguage = input.targetLanguage();
        String purpose = input.purpose();
        log.info("[ToolCall] DiagramExport - 导出图表: target={}, purpose={}", targetLanguage, purpose);

        ArrayNode elementsArray = JsonUtils.createArray();
        for (CanvasElement el : scene.getElements()) {
            ObjectNode elObj = JsonUtils.createObject();
            elObj.put("type", el.getType());
            elObj.put("id", el.getId());
            elObj.put("x", el.getX());
            elObj.put("y", el.getY());
            elObj.put("width", el.getWidth());
            elObj.put("height", el.getHeight());
            elObj.put("strokeColor", el.getStrokeColor());
            elObj.put("fillColor", el.getFillColor());

            // 文字元素额外信息
            if (el instanceof TextElement textEl) {
                elObj.put("text", textEl.getText());
                elObj.put("fontSize", textEl.getFontSize());
            }
            // 自由绘制额外信息
            if (el instanceof FreehandElement freehandEl) {
                elObj.put("pointCount", freehandEl.getPoints().size());
            }

            elementsArray.add(elObj);
        }

        ObjectNode result = JsonUtils.createObject();
        result.set("elements", elementsArray);
        result.put("elementCount", scene.getElements().size());
        result.put("targetLanguage", targetLanguage);
        result.put("purpose", purpose);

        return ToolResult.success(JsonUtils.toJson(result));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private CanvasScene scene;

        public Builder scene(CanvasScene scene) {
            this.scene = scene;
            return this;
        }

        public DiagramExportTool build() {
            return new DiagramExportTool(scene);
        }
    }
}
