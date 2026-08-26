package cn.bitloom.vm;

import cn.bitloom.agentic.model.ModelConfig;
import cn.bitloom.agentic.model.ModelFactory;
import cn.bitloom.config.ConfigManager;
import cn.bitloom.store.Store;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SettingsPageViewModel {

    private final ConfigManager configManager;
    private final ModelFactory modelFactory;

    @Getter
    private final StringProperty bochaApiKey = new SimpleStringProperty();

    public void loadFromStore() {
        bochaApiKey.set(configManager.getBochaApiKey());
        // 模型列表配置在 ConfigManager 内存中，直接读取
        if ((Store.selectedModel.get() == null || Store.selectedModel.get().isBlank())
                && !configManager.getModelConfigs().isEmpty()) {
            Store.selectedModel.set(configManager.getModelConfigs().get(0).id());
        }
    }

    public void save() {
        configManager.setBochaApiKey(bochaApiKey.get());
        configManager.save();
    }

    public void reset() {
        configManager.setBochaApiKey(null);
        configManager.save();
        loadFromStore();
    }

    // ===== 模型管理 =====

    public java.util.List<ModelConfig> listModels() {
        return configManager.getModelConfigs();
    }

    public void saveModel(ModelConfig config) {
        // 新增模型（id 为空）：用名称派生 id，与已有模型冲突时追加序号保证唯一，避免覆盖
        if (config.id() == null || config.id().isBlank()) {
            config = withUniqueId(config);
        }
        // 新增首个模型时自动选中
        if (configManager.getModelConfigs().isEmpty()) {
            Store.selectedModel.set(config.id());
        }
        configManager.setSelectedModelId(Store.selectedModel.get());
        configManager.saveModelConfig(config);
        modelFactory.invalidate();
        Store.selectedModel.set(configManager.getSelectedModelId());
    }

    /** 为新增模型生成唯一 id（名称派生；冲突则加 -2/-3... 后缀） */
    private ModelConfig withUniqueId(ModelConfig config) {
        String base = config.name().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (base.isBlank()) {
            base = "model";
        }
        String id = base;
        java.util.List<String> existing = configManager.getModelConfigs().stream()
                .map(ModelConfig::id)
                .toList();
        int n = 2;
        while (existing.contains(id)) {
            id = base + "-" + (n++);
        }
        return new ModelConfig(id, config.name(), config.baseUrl(), config.apiKey(),
                config.chatModel(), config.completionsPath());
    }

    public void deleteModel(String id) {
        configManager.deleteModelConfig(id);
        modelFactory.invalidate();
        Store.selectedModel.set(configManager.getSelectedModelId());
    }
}
