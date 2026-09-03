package cn.bitloom.agentic.agent.assembly;

import cn.bitloom.agentic.agent.interceptor.GoalJudgeInterceptor;
import cn.bitloom.agentic.agent.interceptor.GoalJudgeInterceptor.GoalListener;
import cn.bitloom.agentic.goal.GoalManager;
import cn.bitloom.agentic.tool.plan.ExitPlanModeTool;
import cn.bitloom.agentic.tool.plan.ExitPlanModeTool.PlanApprovalListener;
import cn.bitloom.harness.kernel.Plugin;
import cn.bitloom.harness.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * code 模式 Profile — work 插件集之上追加目标闭环（GoalJudge）与计划模式工具裁剪。
 * <p>
 * 计划模式（{@code planModeActive}）、计划提交回调（{@code planListener}）与目标状态
 * 回调（{@code goalListener}）由 code 层 ViewModel 注入；开启计划模式时仅保留只读
 * 探索工具并追加 ExitPlanMode。
 */
public class CodeProfile extends WorkProfile {

    /** Plan Mode 工具白名单：只读探索 + 交互（ExitPlanMode 单独追加）。 */
    private static final Set<String> PLAN_MODE_ALLOWED = Set.of(
            "Read", "Glob", "Grep", "WebFetch", "WebSearch",
            "TodoWrite", "AskUserQuestion",
            "ConversationSearch", "CrossSessionSearch");

    private final GoalManager goalManager;
    private final BooleanSupplier planModeActive;
    private final PlanApprovalListener planListener;
    private final GoalListener goalListener;

    public CodeProfile(AgentAssemblyContext ctx, GoalManager goalManager,
                       BooleanSupplier planModeActive, PlanApprovalListener planListener,
                       GoalListener goalListener) {
        super(ctx);
        this.goalManager = goalManager;
        this.planModeActive = planModeActive;
        this.planListener = planListener;
        this.goalListener = goalListener;
    }

    @Override
    public String name() {
        return "code";
    }

    @Override
    public List<Plugin> plugins() {
        List<Plugin> plugins = new ArrayList<>(super.plugins());
        plugins.add(goalJudgePlugin());
        return plugins;
    }

    /** 目标闭环：独立判断器复核目标达成，未达成自动续轮。 */
    protected Plugin goalJudgePlugin() {
        return new SimplePlugin("goal-judge", c ->
                c.inject(AgentServiceKeys.INTERCEPTORS).add(GoalJudgeInterceptor.builder()
                        .goalManager(goalManager)
                        .sessionManager(ctx.getSessionManager())
                        .chatModel(ctx.getChatModel())
                        .listener(goalListener)
                        .build()));
    }

    /** 覆盖工具集：计划模式下裁剪为只读白名单并追加 ExitPlanMode。 */
    @Override
    protected Plugin toolsPlugin() {
        return new SimplePlugin("tools", c -> {
            List<ToolCallback> tools = buildBaseTools();
            if (planModeActive.getAsBoolean()) {
                tools = new ArrayList<>(tools.stream()
                        .filter(tc -> PLAN_MODE_ALLOWED.contains(tc.definition().name()))
                        .toList());
                tools.add(ExitPlanModeTool.builder().listener(planListener).build().toToolCallback());
            }
            c.inject(AgentServiceKeys.TOOLS).addAll(tools);
        });
    }
}
