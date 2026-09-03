package cn.bitloom.agentic.tool.file;

import cn.bitloom.harness.tool.AbstractTool;
import cn.bitloom.harness.tool.ToolResult;
import cn.bitloom.harness.tool.ToolContext;
import cn.bitloom.harness.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文件写入工具，将内容写入本地文件系统。
 */
@Slf4j
public class WriteTool extends AbstractTool<WriteTool.Input> {

	private static final String DESCRIPTION = """
		将文件写入磁盘，覆盖已有文件。编辑现有文件前必须先 Read。优先编辑现有文件。不要主动创建文档文件(*.md/README)。
		""";

	private WriteTool() {
		super("Write", DESCRIPTION, Input.class);
	}

	public record Input(
		@ToolParam(description = "要写入的文件的绝对路径（必须是绝对路径，不是相对路径）") String filePath,
		@ToolParam(description = "要写入文件的内容") String content
	) {}

	@Override
	public ToolResult execute(Input input, ToolContext context) {
		String filePath = input.filePath();
		String content = input.content() != null ? input.content() : "";

		try {
			Path path = Paths.get(filePath);
			File file = path.toFile();

			boolean fileExists = file.exists();
			String action = fileExists ? "覆盖" : "创建";

			File parentDir = file.getParentFile();
			if (parentDir != null && !parentDir.exists()) {
				if (!parentDir.mkdirs()) {
					return ToolResult.error("无法创建父目录: " + filePath);
				}
			}

			try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
					StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE)) {
				writer.write(content);
			}

			String message = String.format("已%s %s（%d字节）", action, filePath, content.length());

			Map<String, Object> data = new LinkedHashMap<>();
			data.put("file", filePath);
			data.put("bytes", content.length());
			data.put("action", action);

			return ToolResult.success(message, data);

		}
		catch (IOException e) {
			return ToolResult.error("写入文件时出错: " + e.getMessage());
		}
		catch (Exception e) {
			return ToolResult.error("错误: " + e.getMessage());
		}
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		public WriteTool build() {
			return new WriteTool();
		}
	}
}
