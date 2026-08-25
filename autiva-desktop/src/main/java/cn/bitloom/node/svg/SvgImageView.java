package cn.bitloom.node.svg;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import lombok.Getter;
import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;

@Getter
public class SvgImageView extends ImageView {

    /**
     * 已转码图片缓存：key = svgPath|宽x高|strokeOverride。
     * Batik 转码（SVG 解析 + 渲染 + PNG 编码 + 解码）开销大，
     * 目录树等高频渲染场景必须命中缓存而非每次重新转码。
     * 同一 Image 实例可安全共享给多个 ImageView。
     */
    private static final ConcurrentHashMap<String, Image> IMAGE_CACHE = new ConcurrentHashMap<>();

    private String svgPath;
    private boolean loaded = false;
    /** 可选：加载时把 SVG 的 stroke 颜色替换为指定色（用于彩色圆底下的白色/浅色图标），null 表示保持原样。 */
    private String strokeOverride = null;

    public SvgImageView() {
        super();
        fitWidthProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal.doubleValue() > 0 && svgPath != null && !loaded) {
                loadSvg();
            }
        });
        fitHeightProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal.doubleValue() > 0 && svgPath != null && !loaded) {
                loadSvg();
            }
        });
    }

    public SvgImageView(String svgPath) {
        this();
        this.svgPath = svgPath;
    }

    public void setSvgPath(String svgPath) {
        this.svgPath = svgPath;
        this.loaded = false;
        if (getFitWidth() > 0 && getFitHeight() > 0) {
            loadSvg();
        }
    }

    /** 设置 SVG stroke 替换色（如 "#ffffff"），须在首次 load 前调用。 */
    public void setStrokeColor(String hex) {
        this.strokeOverride = hex;
        this.loaded = false;
        if (getFitWidth() > 0 && getFitHeight() > 0) {
            loadSvg();
        }
    }

    private void loadSvg() {
        if (svgPath == null || svgPath.isEmpty() || loaded) {
            return;
        }
        if (getFitWidth() <= 0 || getFitHeight() <= 0) {
            return;
        }
        loaded = true;
        String key = svgPath + "|" + (int) getFitWidth() + "x" + (int) getFitHeight()
                + "|" + (strokeOverride == null ? "" : strokeOverride);
        try {
            setImage(IMAGE_CACHE.computeIfAbsent(key, k -> transcode()));
        } catch (Exception e) {
            System.err.println("Failed to load SVG: " + svgPath + ", " + e.getMessage());
        }
    }

    /** 执行一次 Batik SVG→PNG 转码（仅在缓存未命中时调用）。 */
    private Image transcode() {
        try (InputStream inputStream = SvgImageView.class.getResourceAsStream(svgPath)) {
            if (inputStream == null) {
                System.err.println("Resource not found: " + svgPath);
                return null;
            }
            String svgText = new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (strokeOverride != null) {
                // 仅覆盖 stroke 颜色，保留 fill（fill="none" 的图标不会被误填成实心）
                svgText = svgText.replaceAll("stroke=\"#[0-9a-fA-F]{3,8}\"", "stroke=\"" + strokeOverride + "\"");
            }

            PNGTranscoder transcoder = new PNGTranscoder();
            transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, (float) getFitWidth());
            transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, (float) getFitHeight());

            ByteArrayInputStream bais = new ByteArrayInputStream(svgText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            transcoder.transcode(new TranscoderInput(bais), new TranscoderOutput(baos));

            return new Image(new ByteArrayInputStream(baos.toByteArray()));
        } catch (IOException | TranscoderException e) {
            throw new RuntimeException(e);
        }
    }
}
