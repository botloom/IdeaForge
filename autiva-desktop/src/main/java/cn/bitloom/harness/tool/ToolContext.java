package cn.bitloom.harness.tool;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.function.BiConsumer;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 工具执行上下文 — 对标 spring-ai 的 ToolContext。
 * <p>
 * 实现 {@link Map} 接口（与 spring-ai 一致），既有工具代码中的
 * {@code context.get(key)} 与 {@code context.getContext().get(key)} 两种写法均保持可用。
 * <p>
 * 常规键：sessionId / userId / branch / projectPath / eventSink。
 */
public final class ToolContext implements Map<String, Object> {

    public static final String KEY_SESSION_ID = "sessionId";
    public static final String KEY_USER_ID = "userId";
    public static final String KEY_BRANCH = "branch";
    public static final String KEY_PROJECT_PATH = "projectPath";
    public static final String KEY_EVENT_SINK = "eventSink";

    private final Map<String, Object> data;

    public ToolContext() {
        this.data = new LinkedHashMap<>();
    }

    public ToolContext(Map<String, Object> data) {
        this.data = data != null ? new LinkedHashMap<>(data) : new LinkedHashMap<>();
    }

    public static ToolContext empty() {
        return new ToolContext();
    }

    /** 兼容 spring-ai 写法：返回自身。 */
    public Map<String, Object> getContext() {
        return this;
    }

    /** 便捷读取：字符串值（缺失返回 null）。 */
    public String getString(String key) {
        Object v = data.get(key);
        return v != null ? v.toString() : null;
    }

    /** 便捷读取：session ID。 */
    public String sessionId() {
        return getString(KEY_SESSION_ID);
    }

    /** 便捷读取：分支名（子智能体标识）。 */
    public String branch() {
        return getString(KEY_BRANCH);
    }

    /** 便捷读取：项目路径（code 模式）。 */
    public String projectPath() {
        return getString(KEY_PROJECT_PATH);
    }

    // ========== Map 委托 ==========

    @Override
    public int size() {
        return data.size();
    }

    @Override
    public boolean isEmpty() {
        return data.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return data.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return data.containsValue(value);
    }

    @Override
    public Object get(Object key) {
        return data.get(key);
    }

    @Override
    public Object put(String key, Object value) {
        return data.put(key, value);
    }

    @Override
    public Object remove(Object key) {
        return data.remove(key);
    }

    @Override
    public void putAll(Map<? extends String, ?> m) {
        data.putAll(m);
    }

    @Override
    public void clear() {
        data.clear();
    }

    @Override
    public Set<String> keySet() {
        return data.keySet();
    }

    @Override
    public Collection<Object> values() {
        return data.values();
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        return data.entrySet();
    }

    @Override
    public Object getOrDefault(Object key, Object defaultValue) {
        return data.getOrDefault(key, defaultValue);
    }

    @Override
    public void forEach(BiConsumer<? super String, ? super Object> action) {
        data.forEach(action);
    }

    @Override
    public void replaceAll(BiFunction<? super String, ? super Object, ?> function) {
        data.replaceAll(function);
    }

    @Override
    public Object putIfAbsent(String key, Object value) {
        return data.putIfAbsent(key, value);
    }

    @Override
    public boolean remove(Object key, Object value) {
        return data.remove(key, value);
    }

    @Override
    public boolean replace(String key, Object oldValue, Object newValue) {
        return data.replace(key, oldValue, newValue);
    }

    @Override
    public Object replace(String key, Object value) {
        return data.replace(key, value);
    }

    @Override
    public Object computeIfAbsent(String key, Function<? super String, ?> mappingFunction) {
        return data.computeIfAbsent(key, mappingFunction);
    }

    @Override
    public Object computeIfPresent(String key,
            BiFunction<? super String, ? super Object, ?> remappingFunction) {
        return data.computeIfPresent(key, remappingFunction);
    }

    @Override
    public Object compute(String key,
            BiFunction<? super String, ? super Object, ?> remappingFunction) {
        return data.compute(key, remappingFunction);
    }

    @Override
    public Object merge(String key, Object value,
            BiFunction<? super Object, ? super Object, ?> remappingFunction) {
        return data.merge(key, value, remappingFunction);
    }

    @Override
    public String toString() {
        return "ToolContext" + data;
    }
}
