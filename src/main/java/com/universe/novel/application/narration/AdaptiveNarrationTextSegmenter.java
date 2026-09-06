package com.universe.novel.application.narration;

import com.universe.novel.application.ports.ChapterNarrationBlockExtractorPort;
import com.universe.novel.domain.narration.NarrationTextSegment;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Novel-owned adaptive narration text segmenter.
 * <p>
 * Converts ordered chapter semantic blocks into deterministic, speakable narration segments
 * suitable for managed TTS generation.
 * <p>
 * Key behaviors:
 * <ul>
 *     <li>Decoupled from Markdown AST parsing via {@link ChapterNarrationBlockExtractorPort}.</li>
 *     <li>Preserves semantic block boundaries ("\n\n") between distinct blocks.</li>
 *     <li>Preserves sentence continuation (" ") between sentences within the same split block.</li>
 *     <li>Splits oversized blocks (> hardSplitThreshold) at sentence boundaries without cutting sentences.</li>
 *     <li>Computes deterministic SHA-256 content hashes over exact UTF-8 segment text.</li>
 *     <li>Scales dynamically across short and very long chapters (~20,000+ characters) with variable segment counts.</li>
 * </ul>
 */
@Component
public class AdaptiveNarrationTextSegmenter implements NarrationTextSegmenter {

    public static final int DEFAULT_TARGET_MIN_CHARS = 600;
    public static final int DEFAULT_TARGET_MAX_CHARS = 900;
    public static final int DEFAULT_SOFT_MAX_CHARS = 1000;
    public static final int DEFAULT_HARD_SPLIT_THRESHOLD = 1500;

    private static final Pattern MULTI_WHITESPACE_PATTERN = Pattern.compile("\\s+");

    private final ChapterNarrationBlockExtractorPort blockExtractor;
    private final int targetMinChars;
    private final int targetMaxChars;
    private final int softMaxChars;
    private final int hardSplitThreshold;

    /**
     * Creates a segmenter with recommended default sizing thresholds.
     */
    @Autowired
    public AdaptiveNarrationTextSegmenter(ChapterNarrationBlockExtractorPort blockExtractor) {
        this(
                blockExtractor,
                DEFAULT_TARGET_MIN_CHARS,
                DEFAULT_TARGET_MAX_CHARS,
                DEFAULT_SOFT_MAX_CHARS,
                DEFAULT_HARD_SPLIT_THRESHOLD
        );
    }

    /**
     * Creates a segmenter with custom sizing thresholds.
     */
    public AdaptiveNarrationTextSegmenter(
            ChapterNarrationBlockExtractorPort blockExtractor,
            int targetMinChars,
            int targetMaxChars,
            int softMaxChars,
            int hardSplitThreshold
    ) {
        this.blockExtractor = Objects.requireNonNull(blockExtractor, "Block extractor must not be null.");
        if (targetMinChars <= 0 || targetMaxChars < targetMinChars || softMaxChars < targetMaxChars || hardSplitThreshold < softMaxChars) {
            throw new IllegalArgumentException(
                    "Invalid segmentation thresholds: targetMin <= targetMax <= softMax <= hardSplitThreshold required."
            );
        }
        this.targetMinChars = targetMinChars;
        this.targetMaxChars = targetMaxChars;
        this.softMaxChars = softMaxChars;
        this.hardSplitThreshold = hardSplitThreshold;
    }

    @Override
    public List<NarrationTextSegment> segment(String chapterContent) {
        if (chapterContent == null || chapterContent.isBlank()) {
            return List.of();
        }

        List<String> rawBlocks = blockExtractor.extractBlocks(chapterContent);
        if (rawBlocks == null || rawBlocks.isEmpty()) {
            return List.of();
        }

        // Decompose blocks into segment units (splitting oversized blocks at sentence boundaries)
        List<SegmentUnit> units = new ArrayList<>();
        int blockIndex = 0;

        for (String blockText : rawBlocks) {
            if (blockText == null || blockText.isBlank()) {
                continue;
            }
            String trimmedBlock = blockText.trim();
            if (trimmedBlock.length() <= hardSplitThreshold) {
                units.add(new SegmentUnit(trimmedBlock, blockIndex));
            } else {
                List<String> sentences = splitSentences(trimmedBlock);
                if (sentences.size() <= 1) {
                    // Pathological oversized sentence kept intact without mid-sentence cut
                    units.add(new SegmentUnit(trimmedBlock, blockIndex));
                } else {
                    for (String sentence : sentences) {
                        units.add(new SegmentUnit(sentence, blockIndex));
                    }
                }
            }
            blockIndex++;
        }

        if (units.isEmpty()) {
            return List.of();
        }

        // Adaptively pack units into segments
        List<NarrationTextSegment> segments = new ArrayList<>();
        StringBuilder currentSegment = new StringBuilder();
        int lastPackedBlockIndex = -1;
        int segmentIndex = 0;

        for (SegmentUnit unit : units) {
            String unitText = unit.text();
            if (unitText.isBlank()) {
                continue;
            }

            if (currentSegment.isEmpty()) {
                currentSegment.append(unitText);
                lastPackedBlockIndex = unit.blockIndex();
            } else {
                // Sentences from the SAME oversized block join with space (" ");
                // units from DIFFERENT blocks preserve a paragraph boundary ("\n\n").
                String delimiter = (unit.blockIndex() == lastPackedBlockIndex) ? " " : "\n\n";
                int candidateLength = currentSegment.length() + delimiter.length() + unitText.length();

                if (candidateLength <= targetMaxChars) {
                    currentSegment.append(delimiter).append(unitText);
                    lastPackedBlockIndex = unit.blockIndex();
                } else if (candidateLength <= softMaxChars) {
                    if (currentSegment.length() < targetMinChars) {
                        currentSegment.append(delimiter).append(unitText);
                        lastPackedBlockIndex = unit.blockIndex();
                    } else {
                        flushSegment(segments, currentSegment.toString(), segmentIndex++);
                        currentSegment.setLength(0);
                        currentSegment.append(unitText);
                        lastPackedBlockIndex = unit.blockIndex();
                    }
                } else {
                    flushSegment(segments, currentSegment.toString(), segmentIndex++);
                    currentSegment.setLength(0);
                    currentSegment.append(unitText);
                    lastPackedBlockIndex = unit.blockIndex();
                }
            }
        }

        if (!currentSegment.isEmpty()) {
            flushSegment(segments, currentSegment.toString(), segmentIndex++);
        }

        return List.copyOf(segments);
    }

    private void flushSegment(List<NarrationTextSegment> segments, String rawText, int index) {
        String normalized = normalizeSegmentText(rawText);
        if (!normalized.isBlank()) {
            segments.add(NarrationTextSegment.of(index, normalized));
        }
    }

    private String normalizeSegmentText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFC);
        normalized = normalized.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = normalized.split("\n");
        List<String> cleanedLines = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                cleanedLines.add(MULTI_WHITESPACE_PATTERN.matcher(trimmed).replaceAll(" "));
            } else if (!cleanedLines.isEmpty() && !cleanedLines.get(cleanedLines.size() - 1).isEmpty()) {
                cleanedLines.add("");
            }
        }

        while (!cleanedLines.isEmpty() && cleanedLines.get(cleanedLines.size() - 1).isEmpty()) {
            cleanedLines.remove(cleanedLines.size() - 1);
        }

        return String.join("\n", cleanedLines).trim();
    }

    /**
     * Splits a text block into individual sentences at terminal punctuation boundaries,
     * preserving punctuation, dialogue quotation marks, and preventing false splits in decimal numbers.
     *
     * @param text input block text
     * @return ordered list of sentence strings
     */
    public static List<String> splitSentences(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<String> sentences = new ArrayList<>();
        int length = text.length();
        int start = 0;

        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);

            if (c == '.' || c == '!' || c == '?' || c == '…') {
                // Prevent splitting in decimal numbers (e.g. 3.14 or 1.500.000)
                if (c == '.' && i > 0 && i + 1 < length
                        && Character.isDigit(text.charAt(i - 1))
                        && Character.isDigit(text.charAt(i + 1))) {
                    continue;
                }

                // Consume repeated dots ("...") or combined punctuation ("?!", "!!")
                int endPunct = i;
                while (endPunct + 1 < length) {
                    char nextC = text.charAt(endPunct + 1);
                    if (nextC == '.' || nextC == '!' || nextC == '?' || nextC == '…') {
                        endPunct++;
                    } else {
                        break;
                    }
                }

                // Consume trailing closing quotes or brackets
                int endQuote = endPunct;
                while (endQuote + 1 < length) {
                    char nextC = text.charAt(endQuote + 1);
                    if (isClosingQuoteOrBracket(nextC)) {
                        endQuote++;
                    } else {
                        break;
                    }
                }

                // Sentence boundary requires following whitespace or end-of-text
                boolean isAtEnd = (endQuote + 1 >= length);
                boolean isFollowedByWhitespace = !isAtEnd && Character.isWhitespace(text.charAt(endQuote + 1));

                if (isAtEnd || isFollowedByWhitespace) {
                    String sentence = text.substring(start, endQuote + 1).trim();
                    if (!sentence.isEmpty()) {
                        sentences.add(sentence);
                    }
                    i = endQuote;
                    while (i + 1 < length && Character.isWhitespace(text.charAt(i + 1))) {
                        i++;
                    }
                    start = i + 1;
                }
            }
        }

        if (start < length) {
            String remaining = text.substring(start).trim();
            if (!remaining.isEmpty()) {
                sentences.add(remaining);
            }
        }

        return List.copyOf(sentences);
    }

    private static boolean isClosingQuoteOrBracket(char c) {
        return c == '"' || c == '\'' || c == '”' || c == '’' || c == '»'
                || c == ')' || c == ']' || c == '}' || c == '>';
    }

    private record SegmentUnit(String text, int blockIndex) {
        SegmentUnit {
            Objects.requireNonNull(text, "Unit text must not be null.");
        }
    }
}
