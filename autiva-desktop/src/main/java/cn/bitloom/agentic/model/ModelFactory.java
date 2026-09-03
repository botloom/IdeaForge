package cn.bitloom.agentic.model;

import cn.bitloom.config.ConfigManager;
import cn.bitloom.harness.llm.ChatModel;
import cn.bitloom.harness.llm.ChatOptions;
import cn.bitloom.harness.llm.OpenAiCompatChatModel;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class ModelFactory {
    private final ConfigManager configManager;
    /** id → ChatModel 缓存（配置更新时失效重建） */
    private final Map<String, ChatModel> cache = new ConcurrentHashMap<>();

    /** 模型配置变化信号：重建前调用 */
    public void invalidate() {
        cache.clear();
    }

    /** 可选模型列表（对话区下拉数据源） */
    public List<ModelConfig> listModels() {
        return configManager.getModelConfigs();
    }

    /**
     * 按模型 id 构建 ChatModel；id 为空或未命中时回退到第一个模型。
     */
    public ChatModel model(String id) {
        ModelConfig config = configManager.findModelConfig(id);
        if (config == null) {
            throw new IllegalStateException("未配置任何模型，请先在设置页添加模型");
        }
        return cache.computeIfAbsent(config.id(), k -> build(config));
    }

    private ChatModel build(ModelConfig config) {
        ChatOptions defaults = new ChatOptions(
                config.chatModel(), null, null, Map.of("thinking", Map.of("type", "enabled")));
        return new OpenAiCompatChatModel(
                config.baseUrl(), config.apiKey(), config.chatModel(),
                config.completionsPath(), defaults);
    }

}
