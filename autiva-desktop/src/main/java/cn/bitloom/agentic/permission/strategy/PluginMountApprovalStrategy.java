package cn.bitloom.agentic.permission.strategy;

import cn.bitloom.agentic.permission.ApprovalService;
import cn.bitloom.agentic.permission.model.ApprovalDecision;
import cn.bitloom.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * PluginMount 工具的审批策略 — 动态插件挂载（自修改）批准。
 *
 * <p>插件挂载会改变智能体自身能力，与文件写操作同级对待：
 * 首次挂载需用户批准，永久批准后该项目内自动放行（批准 key = "PluginMount"）。
 * work 模式（projectDir 为 null）跳过批准。
 *
 * <p>PluginUnmount / PluginList 不需批准：卸载是可逆的减能力操作，列表为只读自省。
 */
@Slf4j
public class PluginMountApprovalStrategy implements ToolApprovalStrategy {

    private final ApprovalService approvalService;

    public PluginMountApprovalStrategy(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @Override
    public String toolName() {
        return "PluginMount";
    }

    @Override
    public String approve(String toolName, String input, String projectDir, String sessionId) {
        String summary = buildSummary(input);
        try {
            ApprovalDecision decision = approvalService.checkAndApprovePlugin(
                    toolName(), "挂载插件", summary, projectDir, sessionId);
            if (!decision.allowed()) {
                log.info("[PermissionHook] 插件挂载被拒绝: summary={}, reason={}", summary, decision.message());
                return "插件挂载被拒绝: " + decision.message();
            }
        } catch (Exception e) {
            log.error("[PermissionHook] 插件审批异常，阻止执行: error={}", e.getMessage(), e);
            return "插件审批失败，已阻止执行: " + e.getMessage();
        }
        return null;
    }

    /**
     * 从工具输入中提取插件摘要（名称/描述/作用域/工具清单）。
     * 解析失败时返回原文截断，保证审批框始终有可读内容。
     */
    private String buildSummary(String input) {
        try {
            JsonNode root = JsonUtils.parse(input);
            String pluginJson = root.path("pluginJson").asText(null);
            String scope = root.path("scope").asText("session");
            if (pluginJson == null || pluginJson.isBlank()) {
                return "（缺少插件声明）";
            }
            JsonNode plugin = JsonUtils.parse(pluginJson);
            StringBuilder sb = new StringBuilder();
            sb.append("插件: ").append(plugin.path("name").asText("(未命名)"));
            sb.append("（作用域 ").append(scope).append("）");
            String description = plugin.path("description").asText(null);
            if (description != null && !description.isBlank()) {
                sb.append("\n").append(description);
            }
            List<String> tools = new ArrayList<>();
            plugin.path("tools").forEach(t -> tools.add(t.path("name").asText("?")));
            if (!tools.isEmpty()) {
                sb.append("\n新增工具: ").append(String.join(", ", tools));
            }
            return sb.toString();
        } catch (Exception e) {
            return input != null && input.length() > 200 ? input.substring(0, 200) + "…" : input;
        }
    }
}
