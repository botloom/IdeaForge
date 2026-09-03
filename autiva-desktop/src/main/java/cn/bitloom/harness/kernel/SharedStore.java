package cn.bitloom.harness.kernel;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 共享存储 — 同一 {@link PluginManager} 下所有插件 ctx 共享的服务表与订阅表。
 * <p>
 * 包私有：仅供 kernel 内部（HarnessContext 与 PluginManager）使用，避免向插件作者
 * 暴露底层并发容器。服务表按 {@link ServiceKey#name()} 索引，订阅表按 {@link EventKey#name()}
 * 索引，均并发安全。
 */
final class SharedStore {

    final Map<String, Object> services = new ConcurrentHashMap<>();

    final Map<String, List<Consumer<Object>>> subscribers = new ConcurrentHashMap<>();

}
