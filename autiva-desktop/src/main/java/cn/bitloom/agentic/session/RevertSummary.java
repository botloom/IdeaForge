package cn.bitloom.agentic.session;

import java.util.List;

/**
 * 轮次撤回结果摘要。
 *
 * @param success       是否成功
 * @param error         失败原因（success=false 时）
 * @param restoredFiles 恢复了原内容的文件（AI 曾修改过的已存在文件）
 * @param deletedFiles  删除的文件（AI 新建的文件）
 */
public record RevertSummary(boolean success, String error,
                            List<String> restoredFiles, List<String> deletedFiles) {

    public static RevertSummary ok(List<String> restoredFiles, List<String> deletedFiles) {
        return new RevertSummary(true, null,
                List.copyOf(restoredFiles), List.copyOf(deletedFiles));
    }

    public static RevertSummary failure(String error) {
        return new RevertSummary(false, error, List.of(), List.of());
    }

    /** 涉及的文件总数 */
    public int fileCount() {
        return restoredFiles.size() + deletedFiles.size();
    }
}
