package com.universe.novel.infrastructure.markdown;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * CommonMark infrastructure adapter for extracting speakable semantic blocks from Markdown.
 */
final class CommonMarkNarrationSourceBlocks {

    private static final Pattern UNPROVEABLE_DECORATION_PATTERN = Pattern.compile("[*=_~-]{3,}");
    private static final Pattern EXCESSIVE_DOTS_PATTERN = Pattern.compile("\\.{4,}");
    private static final Pattern SPECIAL_SPACES_PATTERN = Pattern.compile("[\\u00A0\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000\\uFEFF]");
    private static final Pattern MULTI_WHITESPACE_PATTERN = Pattern.compile("\\s+");

    private final Parser parser;

    CommonMarkNarrationSourceBlocks() {
        List<Extension> extensions = List.of(TablesExtension.create());
        this.parser = Parser.builder().extensions(extensions).build();
    }

    record SourceBlock(Node node, String text) {}
    record SourceDocument(Node document, List<SourceBlock> blocks) {}

    SourceDocument parse(String markdown) {
        Node document = parser.parse(markdown == null ? "" : markdown);
        List<SourceBlock> blocks = new ArrayList<>();
        collectSemanticBlocks(document, blocks);
        return new SourceDocument(document, List.copyOf(blocks));
    }

    private void collectSemanticBlocks(Node node, List<SourceBlock> blocks) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Paragraph || child instanceof Heading) {
                StringBuilder sb = new StringBuilder();
                extractInlineText(child, sb);
                String normalized = cleanBlockText(sb.toString());
                if (!normalized.isBlank()) {
                    blocks.add(new SourceBlock(child, normalized));
                }
            } else if (child instanceof BlockQuote) {
                collectSemanticBlocks(child, blocks);
            } else if (child instanceof ListBlock) {
                for (Node item = child.getFirstChild(); item != null; item = item.getNext()) {
                    if (item instanceof ListItem) {
                        StringBuilder sb = new StringBuilder();
                        extractInlineText(item, sb);
                        String normalized = cleanBlockText(sb.toString());
                        if (!normalized.isBlank()) {
                            blocks.add(new SourceBlock(item, normalized));
                        }
                    }
                }
            } else if (child instanceof FencedCodeBlock fencedCodeBlock) {
                String normalized = cleanBlockText(fencedCodeBlock.getLiteral());
                if (!normalized.isBlank()) {
                    blocks.add(new SourceBlock(child, normalized));
                }
            } else if (child instanceof IndentedCodeBlock indentedCodeBlock) {
                String normalized = cleanBlockText(indentedCodeBlock.getLiteral());
                if (!normalized.isBlank()) {
                    blocks.add(new SourceBlock(child, normalized));
                }
            } else if (child instanceof TableBlock) {
                collectTableBlocks(child, blocks);
            } else if (child instanceof ThematicBreak || child instanceof HtmlBlock) {
                // Non-spoken formatting nodes
            } else {
                collectSemanticBlocks(child, blocks);
            }
        }
    }

    private void collectTableBlocks(Node tableBlock, List<SourceBlock> blocks) {
        for (Node child = tableBlock.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof TableHead || child instanceof TableBody) {
                for (Node row = child.getFirstChild(); row != null; row = row.getNext()) {
                    if (row instanceof TableRow) {
                        StringBuilder sb = new StringBuilder();
                        for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                            if (cell instanceof TableCell) {
                                StringBuilder cellSb = new StringBuilder();
                                extractInlineText(cell, cellSb);
                                String cellText = cleanBlockText(cellSb.toString());
                                if (!cellText.isEmpty()) {
                                    if (!sb.isEmpty()) {
                                        sb.append(", ");
                                    }
                                    sb.append(cellText);
                                }
                            }
                        }
                        String rowText = cleanBlockText(sb.toString());
                        if (!rowText.isEmpty()) {
                            blocks.add(new SourceBlock(row, rowText));
                        }
                    }
                }
            }
        }
    }

    private void extractInlineText(Node parent, StringBuilder sb) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Text text) {
                sb.append(text.getLiteral());
            } else if (child instanceof SoftLineBreak || child instanceof HardLineBreak) {
                sb.append(' ');
            } else if (child instanceof Emphasis || child instanceof StrongEmphasis) {
                extractInlineText(child, sb);
            } else if (child instanceof Link) {
                // Speak the link text only, omit URL destination
                extractInlineText(child, sb);
            } else if (child instanceof Image) {
                // Do not speak images
            } else if (child instanceof Code code) {
                sb.append(code.getLiteral());
            } else if (child instanceof HtmlInline) {
                // Do not speak raw HTML tags
            } else if (child instanceof Paragraph || child instanceof Heading) {
                extractInlineText(child, sb);
                sb.append(' ');
            } else {
                extractInlineText(child, sb);
            }
        }
    }

    private String cleanBlockText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFC);
        normalized = SPECIAL_SPACES_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = UNPROVEABLE_DECORATION_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = EXCESSIVE_DOTS_PATTERN.matcher(normalized).replaceAll("...");
        normalized = normalized.replace("\r\n", " ").replace("\r", " ").replace("\n", " ").replace("\t", " ");
        normalized = MULTI_WHITESPACE_PATTERN.matcher(normalized).replaceAll(" ");
        return normalized.trim();
    }
}
