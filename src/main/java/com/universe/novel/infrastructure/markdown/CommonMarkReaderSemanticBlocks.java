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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CommonMark infrastructure adapter for parsing canonical semantic Reader blocks from Markdown.
 * Serves as the neutral semantic block provider for both normal Reader and narration.
 */
final class CommonMarkReaderSemanticBlocks {

    private final Parser parser;

    CommonMarkReaderSemanticBlocks() {
        List<Extension> extensions = List.of(TablesExtension.create());
        this.parser = Parser.builder().extensions(extensions).build();
    }

    record SemanticBlock(Node node, String blockKind, String blockKey, String rawText, List<String> rawCells) {}
    record SemanticDocument(Node document, List<SemanticBlock> blocks) {}

    private record RawReaderBlock(Node node, String blockKind, String fingerprintContent, String rawText, List<String> rawCells) {}

    SemanticDocument parse(String markdown) {
        Node document = parser.parse(markdown == null ? "" : markdown);
        List<RawReaderBlock> rawBlocks = new ArrayList<>();
        collectSemanticBlocks(document, rawBlocks);

        Map<String, Integer> occurrenceTracker = new HashMap<>();
        List<SemanticBlock> blocks = new ArrayList<>(rawBlocks.size());
        for (RawReaderBlock raw : rawBlocks) {
            String fingerprint = ReaderBlockKeyCalculator.computeFingerprint(raw.blockKind(), raw.fingerprintContent());
            int occurrence = occurrenceTracker.merge(fingerprint, 1, Integer::sum);
            String blockKey = ReaderBlockKeyCalculator.formatBlockKey(fingerprint, occurrence);
            blocks.add(new SemanticBlock(raw.node(), raw.blockKind(), blockKey, raw.rawText(), raw.rawCells()));
        }

        return new SemanticDocument(document, List.copyOf(blocks));
    }

    private void collectSemanticBlocks(Node node, List<RawReaderBlock> blocks) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Paragraph) {
                StringBuilder sb = new StringBuilder();
                extractInlineText(child, sb);
                String rawText = sb.toString();
                String normalized = ReaderBlockKeyCalculator.normalizeProseText(rawText);
                if (!normalized.isBlank()) {
                    blocks.add(new RawReaderBlock(child, "paragraph", normalized, rawText, List.of()));
                }
            } else if (child instanceof Heading) {
                StringBuilder sb = new StringBuilder();
                extractInlineText(child, sb);
                String rawText = sb.toString();
                String normalized = ReaderBlockKeyCalculator.normalizeProseText(rawText);
                if (!normalized.isBlank()) {
                    blocks.add(new RawReaderBlock(child, "heading", normalized, rawText, List.of()));
                }
            } else if (child instanceof BlockQuote) {
                collectSemanticBlocks(child, blocks);
            } else if (child instanceof ListBlock) {
                for (Node item = child.getFirstChild(); item != null; item = item.getNext()) {
                    if (item instanceof ListItem) {
                        StringBuilder sb = new StringBuilder();
                        extractInlineText(item, sb);
                        String rawText = sb.toString();
                        String normalized = ReaderBlockKeyCalculator.normalizeProseText(rawText);
                        if (!normalized.isBlank()) {
                            blocks.add(new RawReaderBlock(item, "list_item", normalized, rawText, List.of()));
                        }
                    }
                }
            } else if (child instanceof FencedCodeBlock fencedCodeBlock) {
                String literal = fencedCodeBlock.getLiteral();
                String normalizedCode = ReaderBlockKeyCalculator.normalizeCodeText(literal);
                if (!normalizedCode.isBlank()) {
                    blocks.add(new RawReaderBlock(child, "code_block", normalizedCode, literal, List.of()));
                }
            } else if (child instanceof IndentedCodeBlock indentedCodeBlock) {
                String literal = indentedCodeBlock.getLiteral();
                String normalizedCode = ReaderBlockKeyCalculator.normalizeCodeText(literal);
                if (!normalizedCode.isBlank()) {
                    blocks.add(new RawReaderBlock(child, "code_block", normalizedCode, literal, List.of()));
                }
            } else if (child instanceof TableBlock) {
                collectTableBlocks(child, blocks);
            } else if (child instanceof ThematicBreak || child instanceof HtmlBlock) {
                // Non-semantic / formatting-only nodes
            } else {
                collectSemanticBlocks(child, blocks);
            }
        }
    }

    private void collectTableBlocks(Node tableBlock, List<RawReaderBlock> blocks) {
        for (Node child = tableBlock.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof TableHead || child instanceof TableBody) {
                for (Node row = child.getFirstChild(); row != null; row = row.getNext()) {
                    if (row instanceof TableRow) {
                        List<String> rawCells = new ArrayList<>();
                        for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                            if (cell instanceof TableCell) {
                                StringBuilder cellSb = new StringBuilder();
                                extractInlineText(cell, cellSb);
                                rawCells.add(cellSb.toString());
                            }
                        }
                        boolean hasContent = rawCells.stream().anyMatch(c -> !c.isBlank());
                        if (hasContent) {
                            String fingerprintContent = ReaderBlockKeyCalculator.formatTableRowFingerprintContent(rawCells);
                            blocks.add(new RawReaderBlock(row, "table_row", fingerprintContent, "", List.copyOf(rawCells)));
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
                // Read link text only, omit URL destination
                extractInlineText(child, sb);
            } else if (child instanceof Image) {
                // Do not include images
            } else if (child instanceof Code code) {
                sb.append(code.getLiteral());
            } else if (child instanceof HtmlInline) {
                // Do not include raw HTML tags
            } else if (child instanceof Paragraph || child instanceof Heading) {
                extractInlineText(child, sb);
                sb.append(' ');
            } else {
                extractInlineText(child, sb);
            }
        }
    }
}
