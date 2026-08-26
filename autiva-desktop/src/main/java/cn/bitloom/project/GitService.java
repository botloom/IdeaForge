package cn.bitloom.project;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Git 服务
 * 查询 Git 信息（如当前分支），并支持分支切换等本地操作
 */
@Slf4j
@Component
public class GitService {

    private static final String GIT_DIR = ".git";

    /**
     * 获取指定路径的当前 Git 分支
     *
     * @param projectPath 项目路径
     * @return 分支名称，非 Git 仓库或查询失败返回 empty
     */
    public Optional<String> getCurrentBranch(Path projectPath) {
        if (projectPath == null || !Files.isDirectory(projectPath)) {
            return Optional.empty();
        }

        // 检查是否是 Git 仓库
        if (!isGitRepository(projectPath)) {
            return Optional.empty();
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD");
            pb.directory(projectPath.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Git 命令超时: {}", projectPath);
                return Optional.empty();
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String branch = reader.readLine();
                if (branch != null && !branch.isEmpty() && process.exitValue() == 0) {
                    return Optional.of(branch.trim());
                }
            }
        } catch (IOException | InterruptedException e) {
            log.warn("获取 Git 分支失败: {}", projectPath, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        return Optional.empty();
    }

    /**
     * 检查路径是否是 Git 仓库
     */
    public boolean isGitRepository(Path projectPath) {
        if (projectPath == null || !Files.isDirectory(projectPath)) {
            return false;
        }
        // 检查当前目录或父目录是否存在 .git
        Path current = projectPath;
        while (current != null) {
            if (Files.exists(current.resolve(GIT_DIR))) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    /**
     * 列出指定路径的所有本地分支名（不含当前分支的 * 标记）。
     *
     * @param projectPath 项目路径
     * @return 本地分支名列表；非 Git 仓库或失败返回空列表
     */
    public List<String> listBranches(Path projectPath) {
        if (!isGitRepository(projectPath)) {
            return List.of();
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "branch", "--format=%(refname:short)");
            pb.directory(projectPath.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Git 列出分支超时: {}", projectPath);
                return List.of();
            }

            List<String> branches = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String branch = line.trim();
                    if (!branch.isEmpty()) {
                        branches.add(branch);
                    }
                }
            }
            return branches;
        } catch (IOException | InterruptedException e) {
            log.warn("列出 Git 分支失败: {}", projectPath, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }
    }

    /**
     * 检查工作区是否干净（无未提交/未暂存的已跟踪文件改动）。
     * 未跟踪文件（untracked）不影响切换分支，故不计入"不干净"。
     *
     * @param projectPath 项目路径
     * @return true 表示已跟踪文件无改动；非 Git 仓库或失败返回 false
     */
    public boolean isWorkingTreeClean(Path projectPath) {
        if (!isGitRepository(projectPath)) {
            return false;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "status", "--porcelain", "--untracked-files=no");
            pb.directory(projectPath.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Git 状态查询超时: {}", projectPath);
                return false;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                boolean clean = reader.readLine() == null;
                if (process.exitValue() == 0) {
                    return clean;
                }
            }
        } catch (IOException | InterruptedException e) {
            log.warn("检查 Git 工作区失败: {}", projectPath, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        return false;
    }

    /**
     * 切换本地分支。
     *
     * @param projectPath 项目路径
     * @param branch      目标分支名
     * @return 是否切换成功
     */
    public boolean switchBranch(Path projectPath, String branch) {
        if (projectPath == null || branch == null || branch.isBlank()) {
            return false;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "checkout", branch);
            pb.directory(projectPath.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Git 切换分支超时: {} -> {}", projectPath, branch);
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            log.warn("切换 Git 分支失败: {} -> {}", projectPath, branch, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }
}
