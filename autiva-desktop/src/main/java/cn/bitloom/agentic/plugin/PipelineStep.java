package cn.bitloom.agentic.plugin;

import java.util.Map;

/**
 * 流水线步骤：调用一个既有工具，参数为模板。
 * <p>
 * 参数值（字符串）支持占位符：
 * <ul>
 *   <li>{@code ${input.xxx}} — 动态工具的入参字段</li>
 *   <li>{@code ${stepN}} — 第 N 步（1 起）的输出，超过 4000 字符截断</li>
 * </ul>
 *
 * @param tool 既有工具名
 * @param args 参数模板（固定值 + 占位符）
 */
public record PipelineStep(String tool, Map<String, Object> args) {
}
