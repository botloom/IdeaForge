package cn.bitloom.node.message;

/**
 * Fence 感知的流式 Markdown 块边界扫描器（{@link AssistantMessageCard} 与
 * {@link ReasoningCard} 流式增量渲染共用）。
 * <p>
 * 安全边界 = 不在未闭合围栏（``` / ~~~）内的空行结束处：空行后的新块一旦开始，
 * 之前的所有顶层块即定稿，且切片独立解析与全文解析结构一致。
 * 另有列表项伪边界（顶层列表项行首，仅当上一完整行为空行或列表项，避免切断段落语义），
 * 在尾块超过 {@link #TAIL_SPLIT_THRESHOLD} 时选用，防止长列表整体滞留尾块导致 O(n²)。
 * 扫描按完整行增量推进；未闭合围栏与文末残行不产生边界，偏差由定格/完成时的整体重渲染兜底。
 */
final class MarkdownBlockBoundaryScanner {

    /** 尾块超过该字符数才启用列表项伪边界切分：典型短列表整体留在尾块内保持原生块结构（无拆分缝隙） */
    private static final int TAIL_SPLIT_THRESHOLD = 1200;

    private int scanPos = 0;
    private boolean inFence = false;
    private char fenceChar = 0;
    private int fenceLen = 0;
    /** 上一完整行是否为空行或顶层列表项（列表项伪边界的前置条件） */
    private boolean prevLineSettles = false;
    /** 最新的列表项伪边界候选（行首偏移），供达到阈值时选用 */
    private int itemCutCandidate = -1;

    void reset() {
        scanPos = 0;
        inFence = false;
        fenceChar = 0;
        fenceLen = 0;
        prevLineSettles = false;
        itemCutCandidate = -1;
    }

    /**
     * 增量扫描新增完整行，返回建议的定稿边界（下一块起点偏移）。
     * 边界只前进不回退；无新边界时返回 settledUpto。
     * 传入的 settledUpto 须与调用方已定稿渲染位置一致（也是本调用返回值的下限）。
     */
    int advance(String text, int settledUpto) {
        int boundary = Math.min(settledUpto, text.length());
        // 只扫描以换行结尾的完整行，文末残行留待下次（其内容还可能变化）
        int limit = text.lastIndexOf('\n') + 1;
        int i = Math.min(scanPos, limit);
        while (i < limit) {
            int lineEnd = text.indexOf('\n', i);
            lineEnd = lineEnd == -1 ? limit : lineEnd + 1;
            String line = text.substring(i, lineEnd);
            if (inFence) {
                if (isClosingFence(line, fenceChar, fenceLen)) {
                    inFence = false;
                }
                prevLineSettles = false;
            } else if (isFenceOpen(line)) {
                inFence = true;
                int s = 0;
                while (line.charAt(s) == ' ') s++;
                fenceChar = line.charAt(s);
                fenceLen = 0;
                while (s + fenceLen < line.length() && line.charAt(s + fenceLen) == fenceChar) fenceLen++;
                prevLineSettles = false;
            } else if (line.isBlank()) {
                // 跳过连续空行；空行后仍有完整行内容才提交定稿
                int j = lineEnd;
                while (j < limit) {
                    int le2 = text.indexOf('\n', j);
                    le2 = le2 == -1 ? limit : le2 + 1;
                    if (!text.substring(j, le2).isBlank()) break;
                    j = le2;
                }
                if (j < limit && j > boundary) {
                    boundary = j;
                }
                i = j;
                prevLineSettles = true;
                continue;
            } else if (isTopLevelListItem(line)) {
                if (prevLineSettles) {
                    itemCutCandidate = i;
                }
                prevLineSettles = true;
            } else {
                prevLineSettles = false;
            }
            i = lineEnd;
        }
        scanPos = limit;
        // 长列表防退化：尾块足够大时才采纳列表项伪边界
        if (itemCutCandidate > boundary
                && itemCutCandidate - settledUpto >= TAIL_SPLIT_THRESHOLD) {
            boundary = itemCutCandidate;
        }
        return boundary;
    }

    /** 围栏开启行：0-3 空格缩进 + 3 个以上连续 ` 或 ~。反引号围栏的 info string 不能含 `。 */
    private static boolean isFenceOpen(String line) {
        int i = 0;
        int n = line.length();
        while (i < n && line.charAt(i) == ' ') i++;
        if (n - i < 3) return false;
        char c = line.charAt(i);
        if (c != '`' && c != '~') return false;
        int run = 0;
        while (i < n && line.charAt(i) == c) {
            run++;
            i++;
        }
        if (run < 3) return false;
        return c != '`' || line.indexOf('`', i) == -1;
    }

    /** 围栏闭合行：0-3 空格缩进 + 同字符不少于开启长度 + 其后仅空白。 */
    private static boolean isClosingFence(String line, char fenceChar, int fenceLen) {
        int i = 0;
        int n = line.length();
        while (i < n && line.charAt(i) == ' ') i++;
        int run = 0;
        while (i < n && line.charAt(i) == fenceChar) {
            run++;
            i++;
        }
        if (run < fenceLen) return false;
        while (i < n) {
            char ch = line.charAt(i++);
            if (ch != ' ' && ch != '\t' && ch != '\n' && ch != '\r') return false;
        }
        return true;
    }

    /** 顶层列表项行首（无缩进）：`- `/`* `/`+ ` 或 1-9 位数字 + `.`/`)` + 空格。 */
    private static boolean isTopLevelListItem(String line) {
        int n = line.length();
        if (n < 2) return false;
        char c0 = line.charAt(0);
        if ((c0 == '-' || c0 == '*' || c0 == '+')
                && (line.charAt(1) == ' ' || line.charAt(1) == '\t')) {
            return true;
        }
        int i = 0;
        while (i < n && Character.isDigit(line.charAt(i))) i++;
        if (i >= 1 && i <= 9 && i + 1 < n) {
            char c = line.charAt(i);
            if ((c == '.' || c == ')') && (line.charAt(i + 1) == ' ' || line.charAt(i + 1) == '\t')) {
                return true;
            }
        }
        return false;
    }
}
