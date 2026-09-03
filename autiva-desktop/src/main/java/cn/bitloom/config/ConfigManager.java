package cn.bitloom.config;

import cn.bitloom.agentic.model.ModelConfig;
import cn.bitloom.agentic.session.SessionIsolationEnum;
import cn.bitloom.constant.AppConstants;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Setter
@Getter
@Slf4j
public class ConfigManager {

    private SessionIsolationEnum isolation = SessionIsolationEnum.PER_PEER;
    private String bochaApiKey = "";

    /** 每轮对话的工具调用预算（防 LLM 工具调用死循环；编码任务需覆盖读文件+修改+编译验证多轮） */
    private int maxToolCalls = 150;

    private String deepseekBaseUrl = "";
    private String deepseekCompletionsPath = "/v1/chat/completions";
    private String deepseekApiKey = "";
    private String deepseekChatModel = "deepseek-chat";

    /** 当前选中的模型 id（未配置时为空，取第一个模型） */
    private String selectedModelId = "";

    /** 多模型列表（app.models.list） */
    private List<Map<String, Object>> modelList = new ArrayList<>();

    /** 兼容读入旧 deepseek 单模型配置：迁移为列表首项 */
    private boolean migratedFromDeepseek = false;

    public ConfigManager() {
        load();
    }

    /** 从 ~/.autiva/settings.yaml 读取配置并填充字段（替代 @Value 注入）。 */
    private void load() {
        try {
            Map<String, Object> root = loadYaml();
            Object isolationVal = getPath(root, "app", "session", "isolation");
            if (isolationVal instanceof String s && !s.isBlank()) {
                try {
                    this.isolation = SessionIsolationEnum.valueOf(s);
                } catch (IllegalArgumentException e) {
                    log.warn("未知的会话隔离级别: {}", s);
                }
            }
            this.bochaApiKey = str(getPath(root, "app", "search", "bocha-api-key"));
            Object maxCalls = getPath(root, "app", "agent", "max-tool-calls");
            if (maxCalls instanceof Number n) {
                this.maxToolCalls = n.intValue();
            }
            this.deepseekBaseUrl = str(getPath(root, "spring", "ai", "deepseek", "chat", "base-url"));
            this.deepseekCompletionsPath = str(getPath(root, "spring", "ai", "deepseek", "chat", "completions-path"));
            this.deepseekApiKey = str(getPath(root, "spring", "ai", "deepseek", "chat", "api-key"));
            this.deepseekChatModel = str(getPath(root, "spring", "ai", "deepseek", "chat", "options", "model"));
            loadModels(root);
        } catch (Exception e) {
            log.error("读取模型配置失败", e);
        }
    }

    private void loadModels(Map<String, Object> root) {
        Object selected = getPath(root, "app", "models", "selected");
        if (selected instanceof String s && !s.isBlank()) {
            this.selectedModelId = s;
        }
        Object list = getPath(root, "app", "models", "list");
        if (list instanceof List<?> items && !items.isEmpty()) {
            this.modelList = new ArrayList<>();
            for (Object item : items) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    map.forEach((k, v) -> m.put(String.valueOf(k), v));
                    this.modelList.add(m);
                }
            }
            return;
        }
        // 旧配置迁移：无列表时用 deepseek 单模型生成首项
        migrateFromDeepseek(root);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadYaml() throws IOException {
        if (!Files.exists(AppConstants.Base.SETTINGS_FILE)) {
            return Map.of();
        }
        try (InputStream in = Files.newInputStream(AppConstants.Base.SETTINGS_FILE)) {
            Yaml yaml = new Yaml();
            Object loaded = yaml.load(in);
            if (loaded instanceof Map) {
                return (Map<String, Object>) loaded;
            }
        }
        return Map.of();
    }

    private Object getPath(Map<String, Object> root, String... path) {
        Object current = root;
        for (String key : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(key);
        }
        return current;
    }

    private void migrateFromDeepseek(Map<String, Object> root) {
        Object baseUrl = getPath(root, "spring", "ai", "deepseek", "chat", "base-url");
        if (baseUrl == null) {
            baseUrl = deepseekBaseUrl;
        }
        if (baseUrl == null || ((String) baseUrl).isBlank()) {
            return;
        }
        Object apiKey = getPath(root, "spring", "ai", "deepseek", "chat", "api-key");
        if (apiKey == null) {
            apiKey = deepseekApiKey;
        }
        Object model = getPath(root, "spring", "ai", "deepseek", "chat", "options", "model");
        if (model == null) {
            model = deepseekChatModel;
        }
        Object completions = getPath(root, "spring", "ai", "deepseek", "chat", "completions-path");
        if (completions == null) {
            completions = deepseekCompletionsPath;
        }
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("id", "deepseek");
        first.put("name", "DeepSeek");
        first.put("base-url", baseUrl);
        first.put("api-key", apiKey != null ? apiKey : "");
        first.put("chat-model", model != null ? model : "deepseek-chat");
        first.put("completions-path", completions != null ? completions : "/v1/chat/completions");
        this.modelList = new ArrayList<>();
        this.modelList.add(first);
        this.migratedFromDeepseek = true;
        if (this.selectedModelId == null || this.selectedModelId.isBlank()) {
            this.selectedModelId = "deepseek";
        }
        log.info("已迁移 DeepSeek 单模型配置为多模型列表");
    }

    /**
     * 返回模型配置（不可变快照）列表。
     */
    public List<ModelConfig> getModelConfigs() {
        List<ModelConfig> configs = new ArrayList<>();
        if (modelList != null) {
            for (Map<String, Object> item : modelList) {
                configs.add(toConfig(item));
            }
        }
        return configs;
    }

    private ModelConfig toConfig(Map<String, Object> item) {
        String id = str(item.get("id"));
        return new ModelConfig(
                id,
                str(item.getOrDefault("name", id)),
                str(item.get("base-url")),
                str(item.get("api-key")),
                str(item.getOrDefault("chat-model", "deepseek-chat")),
                str(item.getOrDefault("completions-path", "/v1/chat/completions")));
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 按 id 查找模型配置；id 为空或未命中时返回第一个模型。
     */
    public ModelConfig findModelConfig(String id) {
        List<ModelConfig> configs = getModelConfigs();
        if (configs.isEmpty()) {
            return null;
        }
        if (id != null && !id.isBlank()) {
            for (ModelConfig config : configs) {
                if (config.id().equals(id)) {
                    return config;
                }
            }
        }
        return configs.get(0);
    }

    /**
     * 保存/更新一个模型配置（按 id 匹配）。
     */
    public void saveModelConfig(ModelConfig config) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", config.id());
        item.put("name", config.name());
        item.put("base-url", config.baseUrl());
        item.put("api-key", config.apiKey());
        item.put("chat-model", config.chatModel());
        item.put("completions-path", config.completionsPath());
        for (int i = 0; i < modelList.size(); i++) {
            if (config.id().equals(str(modelList.get(i).get("id")))) {
                modelList.set(i, item);
                save();
                return;
            }
        }
        modelList.add(item);
        save();
    }

    /**
     * 删除模型配置；删除当前选中项时，选中回退到第一个模型。
     */
    public void deleteModelConfig(String id) {
        modelList.removeIf(item -> id.equals(str(item.get("id"))));
        if (id.equals(selectedModelId)) {
            selectedModelId = modelList.isEmpty() ? "" : str(modelList.get(0).get("id"));
        }
        save();
    }

    /**
     * 上移/下移模型配置（负数上移、正数下移）；越界或 id 不存在时返回 false。
     * 列表第一位即默认模型，排序后选中项跟随新的第一位。
     */
    public boolean moveModelConfig(String id, int offset) {
        for (int i = 0; i < modelList.size(); i++) {
            if (!id.equals(str(modelList.get(i).get("id")))) {
                continue;
            }
            int target = i + offset;
            if (target < 0 || target >= modelList.size()) {
                return false;
            }
            Collections.swap(modelList, i, target);
            selectedModelId = str(modelList.get(0).get("id"));
            save();
            return true;
        }
        return false;
    }

    /**
     * 以 YAML 格式保存配置到 ~/.autiva/settings.yaml
     */
    public void save() {
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("app.session.isolation", isolation.name());
        if (org.apache.commons.lang3.StringUtils.isNotBlank(bochaApiKey)) {
            flat.put("app.search.bocha-api-key", bochaApiKey);
        }

        Map<String, Object> nested = nest(flat);

        // 模型列表（保序）
        if (modelList != null && !modelList.isEmpty()) {
            nested.computeIfAbsent("app", k -> new LinkedHashMap<String, Object>());
            @SuppressWarnings("unchecked")
            Map<String, Object> app = (Map<String, Object>) nested.get("app");
            Map<String, Object> models = new LinkedHashMap<>();
            models.put("selected", selectedModelId != null ? selectedModelId : "");
            models.put("list", modelList);
            app.put("models", models);
        }

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Yaml yaml = new Yaml(options);

        try {
            Files.createDirectories(AppConstants.Base.SETTINGS_FILE.getParent());
            Files.writeString(AppConstants.Base.SETTINGS_FILE, yaml.dump(nested));
            log.info("配置保存成功: {}", AppConstants.Base.SETTINGS_FILE);
        } catch (IOException e) {
            log.error("保存配置文件失败", e);
        }
    }

    /**
     * 将扁平 key（如 "app.search.bocha-api-key"）转为嵌套 Map 结构。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> nest(Map<String, Object> flat) {
        Map<String, Object> root = new LinkedHashMap<>();
        flat.forEach((key, value) -> {
            String[] parts = key.split("\\.");
            Map<String, Object> current = root;
            for (int i = 0; i < parts.length - 1; i++) {
                Object existing = current.get(parts[i]);
                if (!(existing instanceof Map)) {
                    Map<String, Object> node = new LinkedHashMap<>();
                    current.put(parts[i], node);
                    current = node;
                } else {
                    current = (Map<String, Object>) existing;
                }
            }
            current.put(parts[parts.length - 1], value);
        });
        return root;
    }

}
