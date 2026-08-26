package cn.bitloom.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 检测本机已安装的外部工具（IDE / 编辑器 / 终端 / 文件管理器），
 * 供 Code 模式右上角“在外部打开”下拉按钮动态生成菜单。
 * <p>
 * 采用“扫描常见安装路径 + 尝试多级查找 bin + 注册表兜底”的方式，
 * 检测到哪些就返回哪些，保证只列出真实可用、可直接打开项目的工具。
 */
public final class ExternalToolDetector {

    private ExternalToolDetector() {
    }

    /** 工具类型：决定启动时如何注入项目目录 */
    public enum ToolKind {
        /** IDE：把项目目录作为命令行参数传入（如 idea64.exe &lt;dir&gt;） */
        IDE,
        /** 终端：以项目目录作为工作目录打开 */
        TERMINAL,
        /** 文件管理器：把项目目录作为命令行参数传入（如 explorer.exe &lt;dir&gt;） */
        FILE_MANAGER
    }

    /** 检测到的可打开工具 */
    public record DetectedTool(String displayName, List<String> launchCommand, ToolKind kind) {
    }

    /** 是否为 Windows 系统 */
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(ExternalToolDetector.class);

    /**
     * 以指定项目目录为目标，启动外部工具（异步、不阻塞调用线程）。
     * IDE / 文件管理器将项目路径作为参数传入；终端先切换到项目目录再打开新窗口。
     *
     * @param projectDir 项目目录（可相对，内部会转绝对路径）；为 null 则取当前用户目录
     * @param tool       要启动的工具
     */
    public static void launch(String projectDir, DetectedTool tool) {
        Path dir = resolveProjectDir(projectDir);
        try {
            if (tool.kind() == ToolKind.TERMINAL) {
                // 用 start 显式创建独立控制台窗口；工作目录已设为项目目录，
                // 子 cmd 继承该目录并保持打开（/k）。
                Process p = new ProcessBuilder("cmd.exe", "/c",
                                "start", "\"Autiva Terminal\"", "cmd.exe", "/k")
                        .directory(dir.toFile())
                        .start();
                LOG.info("[ExternalTool] 已启动终端, projectDir={}, pid={}", dir, p.pid());
                return;
            }

            List<String> command = new ArrayList<>(tool.launchCommand());
            // IDE / 文件管理器将项目路径作为参数传入
            command.add(dir.toString());

            Process p = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            LOG.info("[ExternalTool] 已启动: {}, cmd={}, projectDir={}", tool.displayName(), command, dir);
        } catch (Exception e) {
            LOG.warn("[ExternalTool] 启动失败: {}, projectDir={}", tool, dir, e);
        }
    }

    private static Path resolveProjectDir(String projectDir) {
        try {
            if (projectDir != null && !projectDir.isBlank()) {
                Path p = Paths.get(projectDir).toAbsolutePath().normalize();
                if (Files.isDirectory(p)) {
                    return p;
                }
            }
        } catch (Exception ignored) {
        }
        return Paths.get(System.getProperty("user.dir"));
    }

    /**
     * 扫描并返回本机已安装的外部工具列表。固定内置：文件管理器、终端。
     * IDE / 编辑器按安装情况动态追加。
     */
    public static List<DetectedTool> detect() {
        Set<DetectedTool> tools = new LinkedHashSet<>();
        if (IS_WINDOWS) {
            tools.add(fileManager());
            tools.add(terminal());
            detectWindows(tools);
        } else {
            tools.add(fileManager());
            tools.add(terminal());
            detectUnix(tools);
        }
        return new ArrayList<>(tools);
    }

    /** 固定项：终端 */
    public static DetectedTool terminal() {
        if (IS_WINDOWS) {
            return new DetectedTool("终端", List.of("cmd.exe", "/k"), ToolKind.TERMINAL);
        }
        return new DetectedTool("终端", List.of("x-terminal-emulator"), ToolKind.TERMINAL);
    }

    /** 固定项：文件管理器 */
    public static DetectedTool fileManager() {
        if (IS_WINDOWS) {
            return new DetectedTool("文件管理器", List.of("explorer.exe"), ToolKind.FILE_MANAGER);
        }
        return new DetectedTool("文件管理器", List.of("nautilus"), ToolKind.FILE_MANAGER);
    }

    private static void detectWindows(Set<DetectedTool> tools) {
        String localAppData = System.getenv("LOCALAPPDATA");
        String programFiles = System.getenv("ProgramFiles");
        String programFilesX86 = System.getenv("ProgramFiles(x86)");
        Path userHome = Paths.get(System.getProperty("user.home", ""));

        // ---- JetBrains 系列 ----
        if (localAppData != null) {
            // 本地 Programs 目录（常见安装位置）
            scanJetBrainsPaths(Paths.get(localAppData, "Programs"), tools);
            // JetBrains Toolbox 应用目录（多级：apps/&lt;product&gt;/&lt;channel&gt;/&lt;version&gt;/bin）
            scanJetBrainsToolbox(Paths.get(localAppData, "JetBrains", "Toolbox", "apps"), tools);
        }
        scanJetBrainsPaths(Paths.get(programFiles, "JetBrains"), tools);
        scanJetBrainsPaths(Paths.get(programFilesX86, "JetBrains"), tools);
        if (programFilesX86 != null) {
            scanJetBrainsPaths(Paths.get(programFilesX86, "Programs"), tools);
        }

        // ---- VS Code 及同类 Electron 编辑器 ----
        if (localAppData != null) {
            scanElectronEditors(Paths.get(localAppData, "Programs"), tools);
        }
        if (programFiles != null) {
            scanElectronEditors(Paths.get(programFiles), tools);
        }

        // ---- 注册表兜底：已安装程序（DisplayName + InstallLocation）----
        detectFromRegistry(tools);

        // ---- PATH 命令兜底（idea / code / cursor 等 CLI）----
        detectFromPathCommands(tools);
    }

    /** 扫描 JetBrains 安装根目录：{root}/&lt;产品名&gt;/bin/&lt;exe&gt; */
    private static void scanJetBrainsPaths(Path root, Set<DetectedTool> tools) {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        try (var stream = Files.list(root)) {
            stream.filter(Files::isDirectory).forEach(entry -> {
                String dirName = entry.getFileName().toString();
                addIfJetBrainsBin(entry, dirName, tools);
            });
        } catch (Exception ignored) {
        }
    }

    /** 扫描 JetBrains Toolbox：{apps}/&lt;product&gt;/&lt;channel&gt;/&lt;version&gt;/bin（多级目录） */
    private static void scanJetBrainsToolbox(Path apps, Set<DetectedTool> tools) {
        if (apps == null || !Files.isDirectory(apps)) {
            return;
        }
        try (var stream = Files.list(apps)) {
            stream.filter(Files::isDirectory).forEach(productDir -> {
                // 多级查找该产品目录下的 bin 目录
                try (var bins = Files.find(productDir, 6,
                        (p, a) -> a.isDirectory() && p.getFileName().toString()
                                .equalsIgnoreCase("bin"))) {
                    bins.forEach(binDir -> {
                        String stem = jetbrainsStem(productDir.getFileName().toString());
                        addIfBinHasExe(binDir, stem, productDir.getFileName().toString(), tools);
                    });
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    /**
     * 判断产品目录下是否存在 JetBrains IDE，并在其 bin 中定位可执行程序。
     */
    private static void addIfJetBrainsBin(Path productDir, String dirName, Set<DetectedTool> tools) {
        Path bin = productDir.resolve("bin");
        if (!Files.isDirectory(bin)) {
            return;
        }
        String stem = jetbrainsStem(dirName);
        addIfBinHasExe(bin, stem, dirName, tools);
    }

    /**
     * 从 JetBrains 产品目录名推导可执行文件主名。
     * 如 "IntelliJ IDEA 2026.1.1"→idea、"PyCharm 2025.1"→pycharm、"WebStorm"→webstorm。
     */
    private static String jetbrainsStem(String dirName) {
        String lower = dirName.toLowerCase();
        for (String[] pair : new String[][]{
                {"intellij", "idea"},
                {"webstorm", "webstorm"},
                {"pycharm", "pycharm"},
                {"datagrip", "datagrip"},
                {"goland", "goland"},
                {"clion", "clion"},
                {"rustrover", "rustrover"},
                {"rubymine", "rubymine"},
                {"appcode", "appcode"},
                {"android", "studio"}
        }) {
            if (lower.contains(pair[0])) {
                return pair[1];
            }
        }
        // 无法识别产品时，尝试去掉空格/数字后去尾部
        String cleaned = lower.replaceAll("[^a-z]", "");
        return cleaned;
    }

    /**
     * 在 bin 目录里查找给定主名的可执行文件（Windows .exe / Linux 脚本）。
     * 找不到精确名时退化为 bin 下任意 *.exe。
     */
    private static void addIfBinHasExe(Path bin, String stem, String displayName, Set<DetectedTool> tools) {
        Path exe = locateExe(bin, stem);
        if (exe != null) {
            tools.add(new DetectedTool(displayName, List.of(exe.toString()), ToolKind.IDE));
        }
    }

    private static Path locateExe(Path bin, String stem) {
        if (Files.isDirectory(bin)) {
            for (String suffix : new String[]{"64.exe", ".exe", ".cmd", ""}) {
                Path candidate = bin.resolve(stem + suffix);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
            // 兜底：bin 下任意 .exe（JetBrains 场景下通常是主程序）
            try (var stream = Files.list(bin)) {
                var found = stream
                        .filter(f -> Files.isRegularFile(f)
                                && f.getFileName().toString().toLowerCase().endsWith(".exe"))
                        .filter(f -> f.getFileName().toString().toLowerCase().contains("64"))
                        .findFirst();
                if (found.isPresent()) {
                    return found.get();
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** 扫描 Electron 编辑器（VS Code / Cursor / Windsurf / VSCodium） */
    private static void scanElectronEditors(Path root, Set<DetectedTool> tools) {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        try (var stream = Files.list(root)) {
            stream.filter(Files::isDirectory).forEach(entry -> {
                String dirName = entry.getFileName().toString().toLowerCase();
                // 目录名 → 可执行文件名
                for (String[] candidate : new String[][]{
                        {"microsoft vs code", "Code.exe"},
                        {"vs code", "Code.exe"},
                        {"cursor", "Cursor.exe"},
                        {"windsurf", "Windsurf.exe"},
                        {"vscodium", "codium.exe"}
                }) {
                    if (dirName.contains(candidate[0])) {
                        Path exe = entry.resolve(candidate[1]);
                        if (Files.isRegularFile(exe)) {
                            tools.add(new DetectedTool(entry.getFileName().toString(),
                                    List.of(exe.toString()), ToolKind.IDE));
                        }
                        break;
                    }
                }
            });
        } catch (Exception ignored) {
        }
    }

    /** 从 Windows 注册表已安装程序清单检测 IDE/编辑器及其安装路径 */
    private static void detectFromRegistry(Set<DetectedTool> tools) {
        String[] roots = {
                "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
                "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
                "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall"
        };
        String[] keywords = {
                "IntelliJ", "PyCharm", "WebStorm", "DataGrip", "GoLand", "CLion",
                "RustRover", "RubyMine", "Android Studio", "Visual Studio Code",
                "Cursor", "Windsurf", "VSCodium", "Eclipse", "NetBeans"
        };

        for (String root : roots) {
            for (String keyword : keywords) {
                try {
                    Process p = new ProcessBuilder("reg", "query", root,
                                    "/s", "/f", keyword, "/t", "REG_SZ", "/d")
                            .redirectErrorStream(true).start();
                    List<String> lines = readLines(p);
                    addIdeFromRegistryLines(lines, tools);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 解析 reg query 输出，提取处于常见 IDE 目录下的可执行程序。
     * 输出形如：
     *   HKEY_...\Uninstall\IntelliJ IDEA 2026.1.1
     *       DisplayName      REG_SZ   IntelliJ IDEA 2026.2.0.1
     *       InstallLocation  REG_SZ   C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.1
     */
    private static void addIdeFromRegistryLines(List<String> lines, Set<DetectedTool> tools) {
        String installLocation = null;
        String displayName = null;
        for (String line : lines) {
            if ((line.startsWith("HKEY") && line.contains("\\")) || line.trim().startsWith("HKEY_LOCAL")) {
                // 新键开始，结算上一个
                flushRegistryEntry(displayName, installLocation, tools);
                installLocation = null;
                displayName = null;
            } else {
                String trimmed = line.trim();
                int vIdx = trimmed.indexOf("REG_SZ");
                if (vIdx < 0) {
                    continue;
                }
                String value = trimmed.substring(vIdx + "REG_SZ".length()).trim();
                if (trimmed.startsWith("DisplayName") || trimmed.contains("DisplayName")) {
                    displayName = value;
                } else if (trimmed.startsWith("InstallLocation") || trimmed.contains("InstallLocation")) {
                    installLocation = value;
                }
            }
        }
        flushRegistryEntry(displayName, installLocation, tools);
    }

    /** 根据注册表某项的 DisplayName/InstallLocation 尝试定位其可执行程序（仅识别已知 IDE/编辑器） */
    private static void flushRegistryEntry(String displayName, String installLocation, Set<DetectedTool> tools) {
        if (installLocation == null || installLocation.isBlank()) {
            return;
        }
        Path dir;
        try {
            dir = Paths.get(installLocation.trim().replace("\"", ""));
        } catch (Exception e) {
            return;
        }
        if (!Files.isDirectory(dir)) {
            return;
        }
        String dirLower = dir.getFileName().toString().toLowerCase();

        // ---- VS Code 及同类 Electron 编辑器 ----
        if (dirLower.contains("vs code") || dirLower.contains("cursor")
                || dirLower.contains("windsurf") || dirLower.contains("vscodium")) {
            String exeName = dirLower.contains("cursor") ? "Cursor.exe"
                    : dirLower.contains("windsurf") ? "Windsurf.exe"
                    : dirLower.contains("vscodium") ? "codium.exe"
                    : "Code.exe";
            Path exe = dir.resolve(exeName);
            if (Files.isRegularFile(exe)) {
                tools.add(new DetectedTool(dir.getFileName().toString(), List.of(exe.toString()), ToolKind.IDE));
            }
            return;
        }

        // ---- Android Studio / Eclipse / NetBeans（安装目录主 exe）----
        if (dirLower.contains("android") || dirLower.contains("studio")) {
            Path exe = findExeByName(dir, new String[]{"studio64.exe", "studio.exe", "studio.bat"});
            if (exe != null) {
                tools.add(new DetectedTool(dir.getFileName().toString(), List.of(exe.toString()), ToolKind.IDE));
                return;
            }
        }
        if (dirLower.contains("eclipse")) {
            Path exe = findExeByName(dir, new String[]{"eclipse.exe", "eclipsec.exe"});
            if (exe != null) {
                tools.add(new DetectedTool(dir.getFileName().toString(), List.of(exe.toString()), ToolKind.IDE));
                return;
            }
        }
        if (dirLower.contains("netbeans")) {
            Path exe = findExeByName(dir, new String[]{"netbeans.exe", "netbeans64.exe"});
            if (exe != null) {
                tools.add(new DetectedTool(dir.getFileName().toString(), List.of(exe.toString()), ToolKind.IDE));
                return;
            }
        }

        // ---- JetBrains 系列：bin 下按产品主名定位 exe ----
        if (isJetBrainsDir(dirLower)) {
            Path bin = dir.resolve("bin");
            if (Files.isDirectory(bin)) {
                String stem = jetbrainsStem(dir.getFileName().toString());
                Path exe = locateExe(bin, stem);
                if (exe != null) {
                    tools.add(new DetectedTool(dir.getFileName().toString(), List.of(exe.toString()), ToolKind.IDE));
                }
            }
        }
        // 以上都不识别则忽略（避免把 git / tim 等非 IDE 软件误报）
    }

    /** 是否属于已知 JetBrains 产品目录名 */
    private static boolean isJetBrainsDir(String dirLower) {
        return dirLower.contains("intellij") || dirLower.contains("pycharm")
                || dirLower.contains("webstorm") || dirLower.contains("datagrip")
                || dirLower.contains("goland") || dirLower.contains("clion")
                || dirLower.contains("rustrover") || dirLower.contains("rubymine")
                || dirLower.contains("appcode") || dirLower.contains("jetbrains")
                || dirLower.contains("idea");
    }

    private static Path findExeByName(Path dir, String[] names) {
        for (String name : names) {
            Path exe = dir.resolve(name);
            if (Files.isRegularFile(exe)) {
                return exe;
            }
        }
        return null;
    }

    private static List<String> readLines(Process p) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(),
                java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line);
            }
        } catch (Exception ignored) {
        }
        return lines;
    }

    /** 通过 PATH 环境里的 CLI 命令检测（code / cursor / idea 等） */
    private static void detectFromPathCommands(Set<DetectedTool> tools) {
        addIfCommandExists(tools, "VS Code", "code");
        addIfCommandExists(tools, "Cursor", "cursor");
        addIfCommandExists(tools, "IntelliJ IDEA", "idea");
    }

    private static void addIfCommandExists(Set<DetectedTool> tools, String name, String cmd) {
        try {
            Process p = new ProcessBuilder("where", cmd)
                    .redirectErrorStream(true).start();
            if (p.waitFor() == 0) {
                tools.add(new DetectedTool(name, List.of(cmd), ToolKind.IDE));
            }
        } catch (Exception ignored) {
        }
    }

    private static void detectUnix(Set<DetectedTool> tools) {
        addIfCommandExists(tools, "VS Code", "code");
        addIfCommandExists(tools, "IntelliJ IDEA", "idea");
    }
}
