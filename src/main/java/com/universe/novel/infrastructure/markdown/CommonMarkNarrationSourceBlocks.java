package com.universe.novel.infrastructure.markdown;

import org.commonmark.node.Node;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Narration adapter over neutral Reader semantic blocks.
 * Provides speakable text cleanup for TTS and chapter narration segmentation.
 */
final class CommonMarkNarrationSourceBlocks {

    private static final Pattern UNPROVEABLE_DECORATION_PATTERN = Pattern.compile("[*=_~-]{3,}");
    private static final Pattern EXCESSIVE_DOTS_PATTERN = Pattern.compile("\\.{4,}");
    private static final Pattern SPECIAL_SPACES_PATTERN = Pattern.compile("[\\u00A0\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000\\uFEFF]");
    private static final Pattern MULTI_WHITESPACE_PATTERN = Pattern.compile("\\s+");

    private final CommonMarkReaderSemanticBlocks semanticBlocks;

    CommonMarkNarrationSourceBlocks() {
        this(new CommonMarkReaderSemanticBlocks());
    }

    CommonMarkNarrationSourceBlocks(CommonMarkReaderSemanticBlocks semanticBlocks) {
        this.semanticBlocks = semanticBlocks;
    }

    record SourceBlock(Node node, String blockKey, String text) {
        public SourceBlock(Node node, String text) {
            this(node, null, text);
        }
    }

    record SourceDocument(Node document, List<SourceBlock> blocks) {}

    SourceDocument parse(String markdown) {
        var semanticDoc = semanticBlocks.parse(markdown);
        List<SourceBlock> blocks = new ArrayList<>(semanticDoc.blocks().size());
        for (var semBlock : semanticDoc.blocks()) {
            String speakableText = toSpeakableText(semBlock);
            blocks.add(new SourceBlock(semBlock.node(), semBlock.blockKey(), speakableText));
        }
        return new SourceDocument(semanticDoc.document(), List.copyOf(blocks));
    }

    private String toSpeakableText(CommonMarkReaderSemanticBlocks.SemanticBlock semBlock) {
        if ("table_row".equals(semBlock.blockKind())) {
            StringBuilder sb = new StringBuilder();
            for (String rawCell : semBlock.rawCells()) {
                String cellText = cleanBlockText(rawCell);
                if (!cellText.isEmpty()) {
                    if (!sb.isEmpty()) {
                        sb.append(", ");
                    }
                    sb.append(cellText);
                }
            }
            return cleanBlockText(sb.toString());
        }
        return cleanBlockText(semBlock.rawText());
    }

    String cleanBlockText(String text) {
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
