package cn.bitloom.agentic.tool.runtime;

import cn.bitloom.agentic.agent.AgentDefinition;
import cn.bitloom.agentic.skill.Skill;
import cn.bitloom.agentic.skill.SkillManager;
import cn.bitloom.harness.loop.LoopInterceptor;
import cn.bitloom.harness.tool.AbstractTool;
import cn.bitloom.harness.tool.ToolResult;
import cn.bitloom.harness.tool.ToolCallback;
import lombok.extern.slf4j.Slf4j;
import cn.bitloom.harness.tool.ToolContext;
import cn.bitloom.harness.tool.ToolParam;
import cn.bitloom.util.Assert;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行时自省工具 — 让智能体查看自己的构成（工具 / 钩子 / 技能 / 模型）。
 * <p>
 * 对应 Cordis "检查运行时插件树" 的能力：智能体在规划自我扩展、
 * 或需要确认自身能力边界时调用。
 * <p>
 * 按智能体构建时注入的快照展示工具与钩子；技能清单来自 SkillManager 实时数据
 * （热重载后即时反映）。
 */
@Slf4j
public class RuntimeInspectTool extends AbstractTool<RuntimeInspectTool.Input> {

    private static final String DESCRIPTION =
            "检查当前智能体的运行时构成。返回当前可用的工具清单、内部钩子、已加载技能和模型配置。"
                    + "在需要了解自身能力、规划自我扩展或验证自我修改是否生效时调用。";

    private final List<ToolCallback> tools;
    private final List<LoopInterceptor> hooks;
    private final SkillManager skillManager;
    private final AgentDefinition definition;
    private final String modelName;

    public record Input(
            @ToolParam(description = "查看目标：tools | hooks | skills | model，留空返回全部", required = false)
            String target
    ) {}

    private RuntimeInspectTool(List<ToolCallback> tools, List<LoopInterceptor> hooks,
                               SkillManager skillManager, AgentDefinition definition, String modelName) {
        super("RuntimeInspect", DESCRIPTION, Input.class);
        Assert.notNull(skillManager, "skillManager不能为null");
        this.tools = List.copyOf(tools);
        this.hooks = List.copyOf(hooks);
        this.skillManager = skillManager;
        this.definition = definition;
        this.modelName = modelName;
    }

    @Override
    public ToolResult execute(Input input, ToolContext context) {
        String target = input == null || input.target() == null || input.target().isBlank()
                ? "all" : input.target().trim().toLowerCase();

        Map<String, Object> data = new LinkedHashMap<>();
        StringBuilder raw = new StringBuilder();

        if ("all".equals(target) || "tools".equals(target)) {
            List<Map<String, String>> toolList = new ArrayList<>();
            raw.append("## 工具（").append(tools.size()).append("）\n");
            for (ToolCallback tc : tools) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("name", tc.definition().name());
                item.put("description", tc.definition().description());
                toolList.add(item);
                raw.append("- ").append(tc.definition().name()).append("\n");
            }
            data.put("tools", toolList);
        }
        if ("all".equals(target) || "hooks".equals(target)) {
            List<Map<String, Object>> hookList = new ArrayList<>();
            raw.append("\n## 内部钩子（").append(hooks.size()).append("）\n");
            for (LoopInterceptor hook : hooks) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", hook.name());
                item.put("order", hook.order());
                hookList.add(item);
                raw.append("- ").append(hook.name()).append(" (order=").append(hook.order()).append(")\n");
            }
            data.put("hooks", hookList);
        }
        if ("all".equals(target) || "skills".equals(target)) {
            List<Skill> skills = skillManager.getAllSkills();
            List<Map<String, String>> skillList = new ArrayList<>();
            raw.append("\n## 已加载技能（").append(skills.size()).append("）\n");
            for (Skill skill : skills) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("name", skill.name());
                item.put("description", skill.description());
                skillList.add(item);
                raw.append("- ").append(skill.name()).append(": ").append(skill.description()).append("\n");
            }
            data.put("skills", skillList);
        }
        if ("all".equals(target) || "model".equals(target)) {
            Map<String, Object> modelInfo = new LinkedHashMap<>();
            modelInfo.put("model", modelName);
            if (definition != null) {
                modelInfo.put("agent", definition.name());
                modelInfo.put("kind", definition.kind() != null ? definition.kind().name() : null);
            }
            data.put("runtime", modelInfo);
            raw.append("\n## 运行时\n");
            raw.append("- 智能体: ").append(definition != null ? definition.name() : "unknown").append("\n");
            raw.append("- 模型: ").append(modelName != null ? modelName : "unknown").append("\n");
        }

        return ToolResult.builder()
                .status(ToolResult.Status.SUCCESS)
                .message("运行时构成检查完成")
                .data(data)
                .rawOutput(raw.toString())
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private List<ToolCallback> tools;
        private List<LoopInterceptor> hooks;
        private SkillManager skillManager;
        private AgentDefinition definition;
        private String modelName;

        public Builder tools(List<ToolCallback> tools) {
            this.tools = tools;
            return this;
        }

        public Builder hooks(List<LoopInterceptor> hooks) {
            this.hooks = hooks;
            return this;
        }

        public Builder skillManager(SkillManager skillManager) {
            this.skillManager = skillManager;
            return this;
        }

        public Builder definition(AgentDefinition definition) {
            this.definition = definition;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public RuntimeInspectTool build() {
            return new RuntimeInspectTool(tools, hooks, skillManager, definition, modelName);
        }
    }
}
