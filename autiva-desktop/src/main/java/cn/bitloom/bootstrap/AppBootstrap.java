package cn.bitloom.bootstrap;

import cn.bitloom.constant.AppConstants;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 应用启动初始化，按照新的目录结构组织：
 * ~/.autiva/
 * ├── settings.json
 * ├── agents/{work,code}/
 * ├── subagents/
 * ├── workspace/{work,code}/
 * └── skills/
 *
 * <p>classpath 资源复制用 JDK {@link Class#getResourceAsStream}（无 Spring 依赖）；
 * 引导资源为固定清单（bootstrap/ 下已知文件），显式列出文件名复制。
 */
@Slf4j
public class AppBootstrap {

    /** 内置子智能体名清单（对应 bootstrap/subagent/{name}/agent.md） */
    private static final String[] SUBAGENTS = {"explore", "general", "plan", "review"};

    private AppBootstrap() {
    }

    public static void initialize() {
        try {
            initBaseDirs();
        } catch (Exception e) {
            log.error("创建应用目录失败", e);
        }
        try {
            initSettingsFile();
        } catch (IOException e) {
            log.error("创建配置文件失败", e);
        }
        try {
            initWorkAgent();
        } catch (Exception e) {
            log.error("初始化 work 主智能体失败", e);
        }
        try {
            initCodeAgent();
        } catch (Exception e) {
            log.error("初始化 code 主智能体失败", e);
        }
        try {
            initSubagents();
        } catch (Exception e) {
            log.error("初始化子智能体失败", e);
        }
        try {
            initWorkMemory();
        } catch (Exception e) {
            log.error("初始化 work 记忆目录失败", e);
        }
        try {
            initProjectsDir();
        } catch (Exception e) {
            log.error("初始化项目目录失败", e);
        }

    }

    private static void initBaseDirs() throws IOException {
        if (!Files.exists(AppConstants.APP_DIR)){
            Files.createDirectories(AppConstants.APP_DIR);
        }
        if (!Files.exists(AppConstants.Base.WORKSPACE_DIR)){
            Files.createDirectories(AppConstants.Base.WORKSPACE_DIR);
        }
        if (!Files.exists(AppConstants.Base.AGENTS_DIR)){
            Files.createDirectories(AppConstants.Base.AGENTS_DIR);
        }
        if (!Files.exists(AppConstants.Base.SUBAGENTS_DIR)){
            Files.createDirectories(AppConstants.Base.SUBAGENTS_DIR);
        }
        if (!Files.exists(AppConstants.Base.SKILLS_DIR)){
            Files.createDirectories(AppConstants.Base.SKILLS_DIR);
        }
        if (!Files.exists(AppConstants.Base.LOGS_DIR)){
            Files.createDirectories(AppConstants.Base.LOGS_DIR);
        }
        // workspace/work/sessions
        Files.createDirectories(AppConstants.Base.WORKSPACE_DIR.resolve("work/sessions"));
        // workspace/code
        Files.createDirectories(AppConstants.Base.WORKSPACE_DIR.resolve("code"));
    }

    private static void initSettingsFile() throws IOException {
        Path settingsFile = AppConstants.Base.SETTINGS_FILE;
        if (Files.exists(settingsFile)) {
            return;
        }
        copyIfAbsent("/bootstrap/settings.yaml", settingsFile);
    }

    /**
     * 初始化 work 主智能体（从 classpath:bootstrap/agent/work/ 复制到 agents/work/）
     */
    private static void initWorkAgent() throws IOException {
        Path agentDir = AppConstants.Agents.agentDir(AppConstants.Agents.WORK_AGENT);
        if (Files.exists(agentDir)) {
            return;
        }
        Files.createDirectories(agentDir);
        copyIfAbsent("/bootstrap/agent/work/agent.md", agentDir.resolve("agent.md"));
        copyIfAbsent("/bootstrap/agent/work/config.json", agentDir.resolve("config.json"));
    }

    /**
     * 初始化 code 主智能体（从 classpath:bootstrap/agent/code/ 复制到 agents/code/）
     */
    private static void initCodeAgent() throws IOException {
        Path agentDir = AppConstants.Agents.agentDir(AppConstants.Agents.CODE_AGENT);
        if (!Files.exists(agentDir)) {
            Files.createDirectories(agentDir);
        }
        copyIfAbsent("/bootstrap/agent/code/agent.md", agentDir.resolve("agent.md"));
        copyIfAbsent("/bootstrap/agent/code/config.json", agentDir.resolve("config.json"));
    }

    /**
     * 初始化子智能体（从 classpath:bootstrap/subagent/{name}/agent.md 复制到
     * ~/.autiva/subagents/{name}/agent.md）。首次启动复制，已存在则跳过。
     */
    private static void initSubagents() throws IOException {
        for (String subagentName : SUBAGENTS) {
            Path targetDir = AppConstants.Agents.subagentDir(subagentName);
            if (Files.exists(targetDir.resolve("agent.md"))) {
                continue;
            }
            Files.createDirectories(targetDir);
            copyIfAbsent("/bootstrap/subagent/" + subagentName + "/agent.md",
                    targetDir.resolve("agent.md"));
            log.info("复制子智能体: {}", subagentName);
        }
    }

    /**
     * 初始化 work 模式记忆目录 workspace/work/memory/MEMORY.md
     */
    private static void initWorkMemory() throws IOException {
        Path memoryDir = AppConstants.Memory.workMemoryDir();
        Path memoryFile = memoryDir.resolve("MEMORY.md");
        if (Files.exists(memoryFile)) {
            return;
        }
        Files.createDirectories(memoryDir);
        copyIfAbsent("/bootstrap/memory-template.md", memoryFile);
    }

    /**
     * 初始化项目目录
     */
    private static void initProjectsDir() throws IOException {
        if (!Files.exists(AppConstants.Base.PROJECTS_DIR)) {
            Files.createDirectories(AppConstants.Base.PROJECTS_DIR);
        }
    }

    /** 从 classpath 复制资源到目标路径（目标已存在则跳过）。 */
    private static void copyIfAbsent(String classpathResource, Path target) throws IOException {
        if (Files.exists(target)) {
            return;
        }
        try (InputStream in = AppBootstrap.class.getResourceAsStream(classpathResource)) {
            if (in == null) {
                log.warn("classpath 资源不存在: {}", classpathResource);
                return;
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

}
