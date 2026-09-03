package cn.bitloom.agentic.plugin;

import java.util.Map;

/**
 * 声明式工具的执行处理器。
 * <p>
 * 两种类型：
 * <ul>
 *   <li>{@code delegate}：委托一个既有工具并覆盖参数（如把 WebSearch 包装为固定领域的搜索工具）</li>
 *   <li>{@code pipeline}：顺序执行多个步骤，前步输出可注入后步参数</li>
 * </ul>
 *
 * @param type  处理器类型：delegate | pipeline
 * @param tool  delegate 模式引用的既有工具名
 * @param args  delegate 模式的参数模板（固定值 + ${input.xxx} 占位符）
 * @param steps pipeline 模式的步骤列表
 */
public record ToolHandler(String type, String tool, Map<String, Object> args, java.util.List<PipelineStep> steps) {
}
