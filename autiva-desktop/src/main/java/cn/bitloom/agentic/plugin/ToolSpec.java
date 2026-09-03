package cn.bitloom.agentic.plugin;

import java.util.Map;

/**
 * 声明式工具定义 — 动态插件提供的能力单元。
 *
 * @param name        工具名（调用名，需以字母开头，可含字母/数字/下划线/连字符）
 * @param description 工具描述（供 LLM 理解何时调用）
 * @param parameters  入参 JSON Schema（object 类型）；可空，空时视为无参工具
 * @param handler     执行处理器（delegate 或 pipeline）
 */
public record ToolSpec(String name, String description, Map<String, Object> parameters, ToolHandler handler) {
}
