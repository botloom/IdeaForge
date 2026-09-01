package cn.bitloom.agentic.snapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 轮次文件快照存储（v1：文件副本后端）。
 * <p>
 * 以内容寻址（SHA-1 命名）存储快照副本，同一 turn 内相同内容自动去重。
 * 后续若快照容量成为瓶颈，可替换为影子 git 对象库实现，调用方无感知。
 */
public final class TurnSnapshotStore {

    private TurnSnapshotStore() {
    }

    /**
     * 写入快照副本，返回内容寻址 ref（sha1 hex）。
     * 同内容只落盘一次。
     */
    public static String put(Path turnDir, byte[] content) throws IOException {
        String ref = sha1Hex(content);
        Path file = turnDir.resolve(ref + ".bin");
        if (Files.notExists(file)) {
            Files.write(file, content);
        }
        return ref;
    }

    /**
     * 按 ref 读取快照内容。
     */
    public static byte[] read(Path turnDir, String ref) throws IOException {
        return Files.readAllBytes(turnDir.resolve(ref + ".bin"));
    }

    private static String sha1Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 不可用", e);
        }
    }
}
