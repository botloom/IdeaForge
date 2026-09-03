package cn.bitloom.harness.loop;

import cn.bitloom.harness.tool.ToolContext;

import java.util.HashMap;
import java.util.Map;

/**
 * 循环上下文 — 合并原 RuntimeContext：一次 AgentLoop.run 的 per-call 可变状态。
 * <p>
 * 携带 sessionId / userId / branch / projectPath 与任意 params（eventSink 等）。
 * 通过 {@link #toToolContext()} 传递给工具层与拦截器。
 */
public class LoopContext {

    /** reactive_compact 重试标记（跳过重复装配/持久化），一次 run 内最多重试一轮 */
    public static final String CTX_REACTIVE_RETRY = "reactiveCompactRetry";

    private final String sessionId;
    private final String userId;
    private final String branch;
    private final String projectPath;
    private final Map<String, Object> params = new HashMap<>();

    public LoopContext(String sessionId, String userId, String branch, String projectPath) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.branch = branch;
        this.projectPath = projectPath;
    }

    public String sessionId() {
        return sessionId;
    }

    public String userId() {
        return userId;
    }

    public String branch() {
        return branch;
    }

    public String projectPath() {
        return projectPath;
    }

    public void put(String key, Object value) {
        params.put(key, value);
    }

    public Object getParam(String key) {
        return params.get(key);
    }

    public <T> T getParam(String key, Class<T> type) {
        Object v = params.get(key);
        return type.isInstance(v) ? type.cast(v) : null;
    }

    /** 事件发布器（Agent 适配层注入的 EventPublisher；类型由 agentic 层定义）。 */
    public Object eventSink() {
        return params.get("eventSink");
    }

    /** 构建 ToolContext（sessionId/userId/branch/projectPath/eventSink + runtimeContext=self）。 */
    public ToolContext toToolContext() {
        Map<String, Object> map = new HashMap<>();
        if (sessionId != null) {
            map.put(ToolContext.KEY_SESSION_ID, sessionId);
        }
        if (userId != null) {
            map.put(ToolContext.KEY_USER_ID, userId);
        }
        if (branch != null) {
            map.put(ToolContext.KEY_BRANCH, branch);
        }
        if (projectPath != null) {
            map.put(ToolContext.KEY_PROJECT_PATH, projectPath);
        }
        Object sink = params.get("eventSink");
        if (sink != null) {
            map.put(ToolContext.KEY_EVENT_SINK, sink);
        }
        map.put("runtimeContext", this);
        return new ToolContext(map);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String sessionId;
        private String userId;
        private String branch;
        private String projectPath;
        private final Map<String, Object> params = new HashMap<>();

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder branch(String branch) {
            this.branch = branch;
            return this;
        }

        public Builder projectPath(String projectPath) {
            this.projectPath = projectPath;
            return this;
        }

        public Builder put(String key, Object value) {
            this.params.put(key, value);
            return this;
        }

        public Builder eventSink(Object eventSink) {
            this.params.put("eventSink", eventSink);
            return this;
        }

        public LoopContext build() {
            LoopContext ctx = new LoopContext(sessionId, userId, branch, projectPath);
            ctx.params.putAll(params);
            return ctx;
        }
    }
}
