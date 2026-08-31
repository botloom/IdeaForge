package cn.bitloom.util;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.*;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.util.Duration;
import lombok.extern.slf4j.Slf4j;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.*;
import org.commonmark.node.Image;

import java.awt.*;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class MarkdownFxRenderer {

    private static final String FONT_FAMILY = "\"SF Pro Text\", -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif";
    private static final String CODE_FONT_FAMILY = "\"SF Mono\", Monaco, \"Cascadia Code\", monospace";
    private static final double BASE_FONT_SIZE = 15;
    private static final double CODE_FONT_SIZE = 13;

    /**
     * 链接处理器：应用启动时由 IndexController 注入。
     * 返回 true 表示已处理（如用项目视图打开文件），false 表示回退到默认行为（浏览器）。
     */
    @FunctionalInterface
    public interface LinkHandler {
        boolean handle(String dest);
    }

    private static volatile LinkHandler linkHandler;

    /**
     * 注入链接处理器（应用启动时调用一次）。
     */
    public static void setLinkHandler(LinkHandler handler) {
        linkHandler = handler;
    }

    private static final org.commonmark.parser.Parser PARSER = org.commonmark.parser.Parser.builder()
        .extensions(java.util.List.of(
            org.commonmark.ext.gfm.tables.TablesExtension.create(),
            StrikethroughExtension.create(),
            TaskListItemsExtension.create(),
            AutolinkExtension.create()
        ))
        .build();

    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+(\\.\\d+)?");
    private static final Pattern WORD_SPLIT_PATTERN = Pattern.compile("(?<=[\\s\\[\\]{}(),;.=+\\-*/<>!&|])|(?=[\\s\\[\\]{}(),;.=+\\-*/<>!&|])");
    private static final Pattern ATX_NO_SPACE = Pattern.compile("^(\\s{0,3}#{1,6})(?=[^#\\s])");

    private static final Set<String> JAVA_KEYWORDS = Set.of(
        "public", "private", "protected", "class", "interface", "extends", "implements",
        "void", "int", "long", "double", "float", "boolean", "char", "String", "return", "if", "else",
        "for", "while", "do", "switch", "case", "break", "continue", "new", "this", "super", "static",
        "final", "abstract", "try", "catch", "finally", "throw", "throws", "import", "package", "null",
        "true", "false", "instanceof"
    );

    private static final Set<String> PYTHON_KEYWORDS = Set.of(
        "def", "class", "if", "elif", "else", "for", "while", "try", "except",
        "finally", "with", "as", "import", "from", "return", "yield", "raise", "pass", "break",
        "continue", "and", "or", "not", "in", "is", "lambda", "True", "False", "None", "self"
    );

    private static final Set<String> JS_KEYWORDS = Set.of(
        "function", "const", "let", "var", "if", "else", "for", "while", "do",
        "switch", "case", "break", "continue", "return", "class", "extends", "new", "this", "super",
        "import", "export", "from", "async", "await", "try", "catch", "finally", "throw", "typeof",
        "instanceof", "null", "undefined", "true", "false"
    );

    /**
     * 打开链接：优先用注入的 handler（如项目视图打开文件），未处理则回退到系统默认。
     * 供卡片（如 MemoryRecallCard）直接复用，保证与 Markdown 渲染链接的打开行为一致。
     */
    public static void openLink(String dest) {
        try {
            // 优先交给注入的 handler（file:// 链接在项目视图中打开）
            LinkHandler handler = linkHandler;
            if (handler != null && handler.handle(dest)) {
                return;
            }
            // 回退：file:// 用 Desktop.open，其它用 Desktop.browse
            if (!Desktop.isDesktopSupported()) return;
            Desktop desktop = Desktop.getDesktop();
            if (dest.startsWith("file:")) {
                File file = new File(new URI(dest));
                if (file.exists()) {
                    desktop.open(file);
                } else {
                    log.warn("链接指向的文件不存在: {}", dest);
                }
            } else {
                desktop.browse(new URI(dest));
            }
        } catch (Exception ex) {
            log.error("Failed to open link: {}", dest, ex);
        }
    }

    public static VBox render(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return new VBox();
        }
        // LLM 输出常写 `##标题`（# 与文字间缺空格），CommonMark 严格模式下不识别，
        // 先做宽容修复（跳过缩进/围栏代码块），再解析
        org.commonmark.node.Node document = PARSER.parse(normalizeAtxHeadings(markdown));
        VBox container = new VBox(8);
        container.getStyleClass().add("md-content");
        container.setMaxWidth(Double.MAX_VALUE);
        container.setFillWidth(true);
        org.commonmark.node.Node child = document.getFirstChild();
        while (child != null) {
            Node fxNode = renderBlock(child);
            if (fxNode != null) {
                container.getChildren().add(fxNode);
            }
            child = child.getNext();
        }
        return container;
    }

    /**
     * 宽容修复 ATX 标题：`#{1,6}` 后紧跟非空白字符时插入一个空格，
     * 使 `##标题` 能被识别为标题。跳过缩进代码块与围栏代码块内部。
     */
    private static String normalizeAtxHeadings(String markdown) {
        String[] lines = markdown.split("\n", -1);
        boolean inFence = false;
        StringBuilder sb = new StringBuilder(markdown.length() + 16);
        for (String line : lines) {
            String stripped = line.stripLeading();
            if (stripped.startsWith("```") || stripped.startsWith("~~~")) {
                inFence = !inFence;
            } else if (!inFence && !line.startsWith("    ") && !line.startsWith("\t")) {
                Matcher m = ATX_NO_SPACE.matcher(line);
                if (m.find()) {
                    line = line.substring(0, m.end()) + " " + line.substring(m.end());
                }
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static Node renderBlock(org.commonmark.node.Node block) {
        return renderBlock(block, 0);
    }

    private static Node renderBlock(org.commonmark.node.Node block, int depth) {
        if (block instanceof Paragraph) return renderParagraph((Paragraph) block);
        if (block instanceof Heading) return renderHeading((Heading) block);
        if (block instanceof FencedCodeBlock) return renderFencedCodeBlock((FencedCodeBlock) block);
        if (block instanceof IndentedCodeBlock) return renderIndentedCodeBlock((IndentedCodeBlock) block);
        if (block instanceof BulletList) return renderBulletList((BulletList) block, depth);
        if (block instanceof OrderedList) return renderOrderedList((OrderedList) block, depth);
        if (block instanceof BlockQuote) return renderBlockQuote((BlockQuote) block);
        if (block instanceof ThematicBreak) return renderThematicBreak();
        if (block instanceof HtmlBlock) return renderHtmlBlock((HtmlBlock) block);
        if (block instanceof org.commonmark.ext.gfm.tables.TableBlock) return renderTable((org.commonmark.ext.gfm.tables.TableBlock) block);
        return null;
    }

    private static Node renderParagraph(Paragraph paragraph) {
        TextFlow textFlow = new TextFlow();
        textFlow.getStyleClass().add("md-paragraph");
        textFlow.setMaxWidth(Double.MAX_VALUE);
        renderInlines(paragraph, textFlow, NORMAL_FORMAT, BASE_FONT_SIZE);
        return textFlow;
    }

    /**
     * 内联文本格式（字重/斜体/删除线可组合嵌套，如 **_~~text~~**）。
     */
    private record InlineFormat(FontWeight weight, FontPosture posture, boolean strike) {
        InlineFormat bold() {
            return new InlineFormat(FontWeight.BOLD, posture, strike);
        }

        InlineFormat italic() {
            return new InlineFormat(weight, FontPosture.ITALIC, strike);
        }

        InlineFormat struck() {
            return new InlineFormat(weight, posture, true);
        }
    }

    private static final InlineFormat NORMAL_FORMAT = new InlineFormat(FontWeight.NORMAL, FontPosture.REGULAR, false);

    private static Node renderHeading(Heading heading) {
        TextFlow textFlow = new TextFlow();
        textFlow.getStyleClass().add("md-heading");
        textFlow.getStyleClass().add("md-heading-" + heading.getLevel());
        textFlow.setMaxWidth(Double.MAX_VALUE);
        // 标题字号/字重/颜色交由 CSS (md-heading-*) 控制，Java 端不 setFont，避免覆盖样式
        renderHeadingInlines(heading, textFlow);
        return textFlow;
    }

    /**
     * 标题内联渲染：Text 不显式 setFont，由 .md-heading-* 的 CSS 控制字号/字重/颜色，
     * 让各级标题真正体现出层级差异。
     */
    private static void renderHeadingInlines(org.commonmark.node.Node parent, TextFlow textFlow) {
        org.commonmark.node.Node child = parent.getFirstChild();
        while (child != null) {
            if (child instanceof org.commonmark.node.Text textNode) {
                textFlow.getChildren().add(new Text(textNode.getLiteral()));
            } else if (child instanceof StrongEmphasis || child instanceof Emphasis || child instanceof Strikethrough) {
                renderHeadingInlines(child, textFlow);
            } else if (child instanceof Code codeNode) {
                Label codeLabel = new Label(codeNode.getLiteral());
                codeLabel.getStyleClass().add("md-inline-code");
                textFlow.getChildren().add(codeLabel);
            } else if (child instanceof Link link) {
                Hyperlink hyperlink = new Hyperlink();
                hyperlink.getStyleClass().add("md-link");
                hyperlink.setFocusTraversable(false);
                hyperlink.setText(extractNodeText(link));
                String dest = link.getDestination();
                hyperlink.setOnAction(e -> openLink(dest));
                textFlow.getChildren().add(hyperlink);
            } else if (child instanceof SoftLineBreak || child instanceof HardLineBreak) {
                textFlow.getChildren().add(new Text("\n"));
            }
            child = child.getNext();
        }
    }

    private static Node renderFencedCodeBlock(FencedCodeBlock codeBlock) {
        VBox container = new VBox();
        container.getStyleClass().add("md-code-block");

        String info = codeBlock.getInfo();
        HBox header = new HBox();
        header.getStyleClass().add("md-code-header");
        if (info != null && !info.isBlank()) {
            Label langLabel = new Label(info.toUpperCase());
            langLabel.getStyleClass().add("md-code-lang");
            header.getChildren().add(langLabel);
        }
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        header.getChildren().add(spacer);

        Label copyBtn = new Label("复制");
        copyBtn.getStyleClass().add("md-code-copy");
        String code = codeBlock.getLiteral();
        copyBtn.setOnMouseClicked(e -> {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            ClipboardContent content = new ClipboardContent();
            content.putString(code);
            clipboard.setContent(content);
            copyBtn.setText("已复制");
            PauseTransition pause = new PauseTransition(Duration.seconds(2));
            pause.setOnFinished(evt -> copyBtn.setText("复制"));
            pause.play();
        });
        header.getChildren().add(copyBtn);
        container.getChildren().add(header);

        TextFlow codeFlow = new TextFlow();
        codeFlow.getStyleClass().add("md-code-content");
        
        List<Text> highlightedTexts = createHighlightedText(code, info);
        codeFlow.getChildren().addAll(highlightedTexts);
        container.getChildren().add(codeFlow);

        return container;
    }

    private static Node renderIndentedCodeBlock(IndentedCodeBlock codeBlock) {
        VBox container = new VBox();
        container.getStyleClass().add("md-code-block");

        TextFlow codeFlow = new TextFlow();
        codeFlow.getStyleClass().add("md-code-content");
        
        List<Text> highlightedTexts = createHighlightedText(codeBlock.getLiteral(), null);
        codeFlow.getChildren().addAll(highlightedTexts);
        container.getChildren().add(codeFlow);

        return container;
    }

    private static final String[] BULLET_MARKERS = {"•", "◦", "▪"};

    private static Node renderBulletList(BulletList list, int depth) {
        VBox container = new VBox(2);
        container.getStyleClass().add("md-list");
        if (depth > 0) {
            container.getStyleClass().add("md-list--nested");
        }
        org.commonmark.node.Node child = list.getFirstChild();
        while (child != null) {
            if (child instanceof ListItem) {
                String marker = BULLET_MARKERS[Math.min(depth, BULLET_MARKERS.length - 1)];
                container.getChildren().add(renderListItem((ListItem) child, marker, depth));
            }
            child = child.getNext();
        }
        return container;
    }

    private static Node renderOrderedList(OrderedList list, int depth) {
        VBox container = new VBox(2);
        container.getStyleClass().add("md-list");
        if (depth > 0) {
            container.getStyleClass().add("md-list--nested");
        }
        int index = list.getStartNumber();
        org.commonmark.node.Node child = list.getFirstChild();
        while (child != null) {
            if (child instanceof ListItem) {
                container.getChildren().add(renderListItem((ListItem) child, index + ".", depth));
                index++;
            }
            child = child.getNext();
        }
        return container;
    }

    private static Node renderListItem(ListItem item, String marker, int depth) {
        HBox hbox = new HBox(6);
        hbox.getStyleClass().add("md-list-item");

        StackPane markerBox = new StackPane();
        markerBox.getStyleClass().add("md-list-marker-box");
        markerBox.setMinWidth(18);
        markerBox.setPrefWidth(18);
        markerBox.setAlignment(Pos.TOP_LEFT);

        // 任务列表条目：ListItem 的第一个子节点是 TaskListItemMarker
        TaskListItemMarker taskMarker = item.getFirstChild() instanceof TaskListItemMarker m ? m : null;

        if (taskMarker != null) {
            CheckBox checkBox = new CheckBox();
            checkBox.getStyleClass().add("md-task-checkbox");
            checkBox.setSelected(taskMarker.isChecked());
            checkBox.setMouseTransparent(true);
            markerBox.getChildren().add(checkBox);
        } else {
            Text markerText = new Text(marker);
            markerText.setFont(Font.font(FONT_FAMILY, BASE_FONT_SIZE));
            markerText.getStyleClass().add("md-list-marker");
            markerBox.getChildren().add(markerText);
        }
        hbox.getChildren().add(markerBox);

        VBox content = new VBox(4);
        content.getStyleClass().add("md-list-item-content");
        if (taskMarker != null && taskMarker.isChecked()) {
            content.getStyleClass().add("md-task-content--done");
        }
        org.commonmark.node.Node child = taskMarker != null ? taskMarker.getNext() : item.getFirstChild();
        while (child != null) {
            Node fxNode = renderBlock(child, depth + 1);
            if (fxNode != null) {
                content.getChildren().add(fxNode);
            }
            child = child.getNext();
        }
        hbox.getChildren().add(content);
        return hbox;
    }

    private static Node renderBlockQuote(BlockQuote quote) {
        HBox hbox = new HBox();
        hbox.getStyleClass().add("md-blockquote");

        Region leftBar = new Region();
        leftBar.getStyleClass().add("md-blockquote-bar");
        leftBar.setPrefWidth(4);
        leftBar.setMinWidth(4);
        hbox.getChildren().add(leftBar);

        VBox content = new VBox(4);
        content.getStyleClass().add("md-blockquote-content");
        org.commonmark.node.Node child = quote.getFirstChild();
        while (child != null) {
            Node fxNode = renderBlock(child);
            if (fxNode != null) {
                content.getChildren().add(fxNode);
            }
            child = child.getNext();
        }
        hbox.getChildren().add(content);
        return hbox;
    }

    private static Node renderThematicBreak() {
        // 分割线：1px 线 + 上下留白全部用代码控制（Separator 与 CSS padding 在
        // 虚拟流容器中不可靠，会导致紧贴上下文字）
        Region line = new Region();
        line.setBackground(new Background(new BackgroundFill(Color.web("#d1d9e0"), CornerRadii.EMPTY, Insets.EMPTY)));
        line.setMinHeight(1);
        line.setPrefHeight(1);
        line.setMaxHeight(1);
        line.setMaxWidth(Double.MAX_VALUE);

        VBox wrapper = new VBox(line);
        wrapper.setPadding(new Insets(16, 0, 16, 0));
        wrapper.setFillWidth(true);
        wrapper.setMaxWidth(Double.MAX_VALUE);
        return wrapper;
    }

    private static Node renderHtmlBlock(HtmlBlock htmlBlock) {
        TextFlow textFlow = new TextFlow();
        textFlow.getStyleClass().add("md-html-block");
        Text text = new Text(htmlBlock.getLiteral());
        text.setFont(Font.font(CODE_FONT_FAMILY, 13));
        textFlow.getChildren().add(text);
        return textFlow;
    }

    private static void renderInlines(org.commonmark.node.Node parent, TextFlow textFlow, InlineFormat fmt, double fontSize) {
        org.commonmark.node.Node child = parent.getFirstChild();
        while (child != null) {
            renderInline(child, textFlow, fmt, fontSize);
            child = child.getNext();
        }
    }

    private static void renderInline(org.commonmark.node.Node inline, TextFlow textFlow, InlineFormat fmt, double fontSize) {
        if (inline instanceof org.commonmark.node.Text textNode) {
            Text text = new Text(textNode.getLiteral());
            text.setFont(Font.font(FONT_FAMILY, fmt.weight(), fmt.posture(), fontSize));
            if (fmt.strike()) {
                text.getStyleClass().add("md-strikethrough");
            }
            textFlow.getChildren().add(text);
        } else if (inline instanceof StrongEmphasis) {
            renderInlines(inline, textFlow, fmt.bold(), fontSize);
        } else if (inline instanceof Emphasis) {
            renderInlines(inline, textFlow, fmt.italic(), fontSize);
        } else if (inline instanceof Strikethrough) {
            renderInlines(inline, textFlow, fmt.struck(), fontSize);
        } else if (inline instanceof Code codeNode) {
            Label codeLabel = new Label(codeNode.getLiteral());
            codeLabel.getStyleClass().add("md-inline-code");
            textFlow.getChildren().add(codeLabel);
        } else if (inline instanceof Link link) {
            Hyperlink hyperlink = new Hyperlink();
            hyperlink.getStyleClass().add("md-link");
            hyperlink.setFocusTraversable(false);
            hyperlink.setText(extractNodeText(link));
            String dest = link.getDestination();
            hyperlink.setOnAction(e -> openLink(dest));
            textFlow.getChildren().add(hyperlink);
        } else if (inline instanceof Image img) {
            String alt = extractNodeText(img);
            Hyperlink imgLink = new Hyperlink();
            imgLink.getStyleClass().add("md-link");
            imgLink.setFocusTraversable(false);
            imgLink.setText(alt != null && !alt.isBlank() ? alt : "图片链接");
            String dest = img.getDestination();
            imgLink.setOnAction(e -> openLink(dest));
            textFlow.getChildren().add(imgLink);
        } else if (inline instanceof SoftLineBreak || inline instanceof HardLineBreak) {
            textFlow.getChildren().add(new Text("\n"));
        } else if (inline instanceof HtmlInline htmlInline) {
            String literal = htmlInline.getLiteral();
            if (literal != null && literal.toLowerCase().matches("<br\\s*/?>")) {
                textFlow.getChildren().add(new Text("\n"));
            } else {
                Text text = new Text(literal);
                text.setFont(Font.font(CODE_FONT_FAMILY, CODE_FONT_SIZE - 1));
                text.getStyleClass().add("md-html-inline");
                textFlow.getChildren().add(text);
            }
        }
    }

    private static Node renderTable(org.commonmark.ext.gfm.tables.TableBlock table) {
        VBox container = new VBox();
        container.getStyleClass().add("md-table");
        
        org.commonmark.node.Node child = table.getFirstChild();
        org.commonmark.ext.gfm.tables.TableHead head = null;
        org.commonmark.ext.gfm.tables.TableBody body = null;
        
        while (child != null) {
            if (child instanceof org.commonmark.ext.gfm.tables.TableHead) {
                head = (org.commonmark.ext.gfm.tables.TableHead) child;
            } else if (child instanceof org.commonmark.ext.gfm.tables.TableBody) {
                body = (org.commonmark.ext.gfm.tables.TableBody) child;
            }
            child = child.getNext();
        }
        
        List<Double> columnWidths = new ArrayList<>();
        
        if (head != null) {
            org.commonmark.node.Node rowNode = head.getFirstChild();
            if (rowNode instanceof TableRow headerTableRow) {
                org.commonmark.node.Node cell = headerTableRow.getFirstChild();
                while (cell != null) {
                    if (cell instanceof TableCell) {
                        String headerText = extractText((TableCell) cell);
                        double width = Math.max(150, computeTextWidth(headerText, true) + 32);
                        columnWidths.add(width);
                    }
                    cell = cell.getNext();
                }
            }
        }
        
        if (body != null) {
            org.commonmark.node.Node rowNode = body.getFirstChild();
            while (rowNode != null) {
                if (rowNode instanceof TableRow row) {
                    org.commonmark.node.Node cell = row.getFirstChild();
                    int colIndex = 0;
                    while (cell != null) {
                        if (cell instanceof TableCell) {
                            String cellText = extractText((TableCell) cell);
                            double width = Math.max(150, computeTextWidth(cellText, false) + 32);
                            if (colIndex < columnWidths.size()) {
                                columnWidths.set(colIndex, Math.max(columnWidths.get(colIndex), width));
                            } else {
                                columnWidths.add(width);
                            }
                            colIndex++;
                        }
                        cell = cell.getNext();
                    }
                }
                rowNode = rowNode.getNext();
            }
        }
        
        if (head != null) {
            HBox headerRow = new HBox();
            headerRow.getStyleClass().add("md-table-header-row");
            org.commonmark.node.Node rowNode = head.getFirstChild();
            if (rowNode instanceof TableRow headerTableRow) {
                org.commonmark.node.Node cell = headerTableRow.getFirstChild();
                int colIndex = 0;
                while (cell != null) {
                    if (cell instanceof org.commonmark.ext.gfm.tables.TableCell) {
                        String headerText = extractText((org.commonmark.ext.gfm.tables.TableCell) cell);
                        Label label = new Label(headerText);
                        label.getStyleClass().add("md-table-header-cell");
                        double width = colIndex < columnWidths.size() ? columnWidths.get(colIndex) : 150;
                        label.setMinWidth(width);
                        label.setPrefWidth(width);
                        label.setMaxWidth(width);
                        headerRow.getChildren().add(label);
                        colIndex++;
                    }
                    cell = cell.getNext();
                }
            }
            container.getChildren().add(headerRow);
        }
        
        if (body != null) {
            org.commonmark.node.Node rowNode = body.getFirstChild();
            List<HBox> rows = new ArrayList<>();
            while (rowNode != null) {
                if (rowNode instanceof TableRow row) {
                    HBox dataRow = new HBox();
                    dataRow.getStyleClass().add("md-table-row");
                    if (rows.size() % 2 == 1) {
                        dataRow.getStyleClass().add("md-table-row-odd");
                    }
                    org.commonmark.node.Node cell = row.getFirstChild();
                    int colIndex = 0;
                    while (cell != null) {
                        if (cell instanceof org.commonmark.ext.gfm.tables.TableCell) {
                            String cellText = extractText((org.commonmark.ext.gfm.tables.TableCell) cell);
                            Label label = new Label(cellText);
                            label.getStyleClass().add("md-table-cell");
                            double width = colIndex < columnWidths.size() ? columnWidths.get(colIndex) : 150;
                            label.setMinWidth(width);
                            label.setPrefWidth(width);
                            label.setMaxWidth(width);
                            label.setWrapText(true);
                            dataRow.getChildren().add(label);
                            colIndex++;
                        }
                        cell = cell.getNext();
                    }
                    rows.add(dataRow);
                }
                rowNode = rowNode.getNext();
            }
            
            for (int i = 0; i < rows.size(); i++) {
                HBox dataRow = rows.get(i);
                if (i == rows.size() - 1) {
                    dataRow.getStyleClass().add("md-table-row-last");
                }
                container.getChildren().add(dataRow);
            }
        }
        
        ScrollPane scrollPane = new ScrollPane(container);
        scrollPane.getStyleClass().add("md-table-scroll");
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setMaxWidth(Double.MAX_VALUE);
        
        return scrollPane;
    }

    private static double computeTextWidth(String text, boolean bold) {
        Font font = Font.font(FONT_FAMILY, bold ? FontWeight.BOLD : FontWeight.NORMAL, 13);
        Text measure = new Text(text);
        measure.setFont(font);
        return measure.getLayoutBounds().getWidth();
    }
    
    private static String extractText(org.commonmark.ext.gfm.tables.TableCell cell) {
        StringBuilder sb = new StringBuilder();
        org.commonmark.node.Node child = cell.getFirstChild();
        while (child != null) {
            if (child instanceof org.commonmark.node.Text) {
                sb.append(((org.commonmark.node.Text) child).getLiteral());
            } else if (child instanceof org.commonmark.node.Code) {
                sb.append(((org.commonmark.node.Code) child).getLiteral());
            } else if (child instanceof org.commonmark.node.StrongEmphasis || child instanceof org.commonmark.node.Emphasis) {
                org.commonmark.node.Node emphChild = child.getFirstChild();
                while (emphChild != null) {
                    if (emphChild instanceof org.commonmark.node.Text) {
                        sb.append(((org.commonmark.node.Text) emphChild).getLiteral());
                    }
                    emphChild = emphChild.getNext();
                }
            }
            child = child.getNext();
        }
        return sb.toString();
    }

    private static String extractNodeText(org.commonmark.node.Node node) {
        StringBuilder sb = new StringBuilder();
        org.commonmark.node.Node child = node.getFirstChild();
        while (child != null) {
            if (child instanceof org.commonmark.node.Text textNode) {
                sb.append(textNode.getLiteral());
            } else if (child instanceof Code code) {
                sb.append(code.getLiteral());
            } else if (child instanceof Emphasis || child instanceof StrongEmphasis) {
                sb.append(extractNodeText(child));
            }
            child = child.getNext();
        }
        return sb.toString();
    }
    
    private static List<Text> createHighlightedText(String code, String language) {
        List<Text> texts = new ArrayList<>();
        
        if (language == null || language.isBlank()) {
            Text text = new Text(code);
            text.setFont(Font.font(CODE_FONT_FAMILY, CODE_FONT_SIZE));
            text.getStyleClass().add("md-code-text");
            texts.add(text);
            return texts;
        }
        
        String[] lines = code.split("\n", -1);
        // 超长代码块降级：整块单 Text（跳过逐词高亮），避免海量节点拖慢滚动
        if (lines.length > 300) {
            Text text = new Text(code);
            text.setFont(Font.font(CODE_FONT_FAMILY, CODE_FONT_SIZE));
            text.getStyleClass().add("md-code-text");
            texts.add(text);
            return texts;
        }
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                texts.add(new Text("\n"));
            }
            String line = lines[i];
            List<Text> lineTexts = highlightLine(line, language.toLowerCase());
            texts.addAll(lineTexts);
        }
        
        return mergeAdjacentTexts(texts);
    }

    /**
     * 合并相邻同样式（同 styleClass）的代码 Text 节点，大幅减少节点数量：
     * 逐词/逐空格高亮会产生海量 Text，滚动时 cell 复用会反复对这些节点做 CSS + layout，
     * 合并后节点数从 O(token 数) 降到 O(样式切换数)。
     */
    private static List<Text> mergeAdjacentTexts(List<Text> texts) {
        if (texts.size() <= 1) {
            return texts;
        }
        List<Text> merged = new ArrayList<>(texts.size());
        for (Text t : texts) {
            if (!merged.isEmpty()) {
                Text last = merged.get(merged.size() - 1);
                if (sameCodeStyle(last, t) && !"\n".equals(last.getText())) {
                    last.setText(last.getText() + t.getText());
                    continue;
                }
            }
            merged.add(t);
        }
        return merged;
    }

    /** 代码 Text 样式比较：每个代码 Text 只带一个 md-code-* 样式类，据此判断是否可合并。 */
    private static boolean sameCodeStyle(Text a, Text b) {
        String ca = a.getStyleClass().isEmpty() ? "" : a.getStyleClass().get(0);
        String cb = b.getStyleClass().isEmpty() ? "" : b.getStyleClass().get(0);
        return ca.equals(cb);
    }
    
    private static List<Text> highlightLine(String line, String language) {
        List<Text> texts = new ArrayList<>();
        
        if (line.isEmpty()) {
            return texts;
        }
        
        switch (language) {
            case "java":
                texts.addAll(highlightLine(line, JAVA_KEYWORDS, '/', '/'));
                break;
            case "python":
            case "py":
                texts.addAll(highlightLine(line, PYTHON_KEYWORDS, '#', null));
                break;
            case "javascript":
            case "js":
            case "typescript":
            case "ts":
                texts.addAll(highlightLine(line, JS_KEYWORDS, '/', '/'));
                break;
            default:
                Text text = new Text(line);
                text.setFont(Font.font(CODE_FONT_FAMILY, CODE_FONT_SIZE));
                text.getStyleClass().add("md-code-text");
                texts.add(text);
        }
        
        return texts;
    }

    private static List<Text> highlightLine(String line, Set<String> keywords, Character singleCommentChar, Character doubleCommentSecondChar) {
        List<Text> texts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            
            // Check for single-line comment
            if (doubleCommentSecondChar != null && c == singleCommentChar && i + 1 < line.length() && line.charAt(i + 1) == doubleCommentSecondChar) {
                if (!current.isEmpty()) {
                    texts.addAll(parseAndHighlight(current.toString(), keywords));
                    current = new StringBuilder();
                }
                Text comment = new Text(line.substring(i));
                comment.setFont(Font.font(CODE_FONT_FAMILY, CODE_FONT_SIZE));
                comment.getStyleClass().add("md-code-comment");
                texts.add(comment);
                return texts;
            }
            
            if (singleCommentChar != null && doubleCommentSecondChar == null && c == singleCommentChar) {
                if (!current.isEmpty()) {
                    texts.addAll(parseAndHighlight(current.toString(), keywords));
                    current = new StringBuilder();
                }
                Text comment = new Text(line.substring(i));
                comment.setFont(Font.font(CODE_FONT_FAMILY, CODE_FONT_SIZE));
                comment.getStyleClass().add("md-code-comment");
                texts.add(comment);
                return texts;
            }
            
            // Check for string literals
            if (c == '"' || c == '\'' || c == '`') {
                if (!current.isEmpty()) {
                    texts.addAll(parseAndHighlight(current.toString(), keywords));
                    current = new StringBuilder();
                }
                int end = line.indexOf(c, i + 1);
                if (end == -1) end = line.length() - 1;
                Text string = new Text(line.substring(i, end + 1));
                string.setFont(Font.font(CODE_FONT_FAMILY, CODE_FONT_SIZE));
                string.getStyleClass().add("md-code-string");
                texts.add(string);
                i = end;
                continue;
            }
            
            current.append(c);
        }
        
        if (!current.isEmpty()) {
            texts.addAll(parseAndHighlight(current.toString(), keywords));
        }
        
        return texts;
    }
    
    private static List<Text> parseAndHighlight(String text, Set<String> keywords) {
        List<Text> texts = new ArrayList<>();
        String[] words = WORD_SPLIT_PATTERN.split(text);
        
        for (String word : words) {
            if (word.isEmpty()) continue;
            
            Text t = new Text(word);
            t.setFont(Font.font(CODE_FONT_FAMILY, CODE_FONT_SIZE));
            
            if (keywords.contains(word)) {
                t.getStyleClass().add("md-code-keyword");
            } else if (NUMBER_PATTERN.matcher(word).matches()) {
                t.getStyleClass().add("md-code-number");
            } else {
                t.getStyleClass().add("md-code-text");
            }
            
            texts.add(t);
        }
        
        return texts;
    }
}
