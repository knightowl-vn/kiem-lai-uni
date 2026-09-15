package com.universe.novel.application.narration;

import com.universe.novel.application.ports.ChapterNarrationBlockExtractorPort;
import com.universe.novel.domain.narration.NarrationTextSegment;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
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
 *     <li>Splits oversized blocks (> hardSplitThreshold) at sentence boundaries and enforces hardSplitThreshold as a strict upper bound on all emitted segments.</li>
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
        return plan(chapterContent).stream().map(NarrationTextSegmentPlan::segment).toList();
    }

    @Override
    public List<NarrationTextSegmentPlan> plan(String chapterContent) {
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
                if (sentences.isEmpty()) {
                    for (String chunk : splitOversizedUnit(trimmedBlock, hardSplitThreshold)) {
                        units.add(new SegmentUnit(chunk, blockIndex));
                    }
                } else {
                    for (String sentence : sentences) {
                        if (sentence == null || sentence.isBlank()) {
                            continue;
                        }
                        String trimmedSentence = sentence.trim();
                        if (trimmedSentence.length() <= hardSplitThreshold) {
                            units.add(new SegmentUnit(trimmedSentence, blockIndex));
                        } else {
                            for (String chunk : splitOversizedUnit(trimmedSentence, hardSplitThreshold)) {
                                units.add(new SegmentUnit(chunk, blockIndex));
                            }
                        }
                    }
                }
            }
            blockIndex++;
        }

        if (units.isEmpty()) {
            return List.of();
        }

        // Adaptively pack units into segments
        List<NarrationTextSegmentPlan> segments = new ArrayList<>();
        Set<Integer> sourceBlockIndexes = new LinkedHashSet<>();
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

                if (candidateLength <= targetMaxChars && candidateLength <= hardSplitThreshold) {
                    currentSegment.append(delimiter).append(unitText);
                    lastPackedBlockIndex = unit.blockIndex();
                } else if (candidateLength <= softMaxChars && candidateLength <= hardSplitThreshold) {
                    if (currentSegment.length() < targetMinChars) {
                        currentSegment.append(delimiter).append(unitText);
                        lastPackedBlockIndex = unit.blockIndex();
                    } else {
                        segmentIndex = flushSegment(segments, currentSegment.toString(), segmentIndex, sourceBlockIndexes);
                        sourceBlockIndexes.clear();
                        currentSegment.setLength(0);
                        currentSegment.append(unitText);
                        lastPackedBlockIndex = unit.blockIndex();
                    }
                } else {
                    segmentIndex = flushSegment(segments, currentSegment.toString(), segmentIndex, sourceBlockIndexes);
                    sourceBlockIndexes.clear();
                    currentSegment.setLength(0);
                    currentSegment.append(unitText);
                    lastPackedBlockIndex = unit.blockIndex();
                }
            }
            sourceBlockIndexes.add(unit.blockIndex());
        }

        if (!currentSegment.isEmpty()) {
            flushSegment(segments, currentSegment.toString(), segmentIndex, sourceBlockIndexes);
        }

        return List.copyOf(segments);
    }

    private int flushSegment(List<NarrationTextSegmentPlan> segments, String rawText, int nextIndex, Set<Integer> sourceBlockIndexes) {
        String normalized = normalizeSegmentText(rawText);
        if (normalized.isBlank()) {
            return nextIndex;
        }
        if (normalized.length() <= hardSplitThreshold) {
            segments.add(new NarrationTextSegmentPlan(NarrationTextSegment.of(nextIndex++, normalized), List.copyOf(sourceBlockIndexes)));
        } else {
            for (String piece : splitOversizedUnit(normalized, hardSplitThreshold)) {
                segments.add(new NarrationTextSegmentPlan(NarrationTextSegment.of(nextIndex++, piece), List.copyOf(sourceBlockIndexes)));
            }
        }
        return nextIndex;
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

    /**
     * Splits an oversized unit into multiple chunks that each strictly satisfy length &lt;= maxChars.
     * Splitting preferentially breaks at whitespace boundaries at or before maxChars.
     * If no whitespace exists within the window (unbroken token), it hard-cuts at maxChars.
     *
     * @param text input oversized text
     * @param maxChars maximum permitted characters per chunk (e.g. hardSplitThreshold)
     * @return ordered non-empty chunks, each &lt;= maxChars
     */
    public static List<String> splitOversizedUnit(String text, int maxChars) {
        if (text == null || text.isBlank() || maxChars <= 0) {
            return List.of();
        }

        String trimmed = text.trim();
        if (trimmed.length() <= maxChars) {
            return List.of(trimmed);
        }

        List<String> chunks = new ArrayList<>();
        int length = trimmed.length();
        int start = 0;

        while (start < length) {
            // Skip leading whitespace if any
            while (start < length && Character.isWhitespace(trimmed.charAt(start))) {
                start++;
            }
            if (start >= length) {
                break;
            }

            int remaining = length - start;
            if (remaining <= maxChars) {
                String chunk = trimmed.substring(start).trim();
                if (!chunk.isEmpty()) {
                    chunks.add(chunk);
                }
                break;
            }

            int targetEnd = start + maxChars;
            // Prevent splitting UTF-16 surrogate pairs
            if (targetEnd > start + 1 && Character.isHighSurrogate(trimmed.charAt(targetEnd - 1))) {
                targetEnd--;
            }

            // If targetEnd falls right on a whitespace boundary
            if (targetEnd < length && Character.isWhitespace(trimmed.charAt(targetEnd))) {
                String chunk = trimmed.substring(start, targetEnd).trim();
                if (!chunk.isEmpty()) {
                    chunks.add(chunk);
                }
                start = targetEnd + 1;
                continue;
            }

            // Search backward for the last whitespace boundary
            int lastWhitespace = -1;
            for (int i = targetEnd - 1; i > start; i--) {
                if (Character.isWhitespace(trimmed.charAt(i))) {
                    lastWhitespace = i;
                    break;
                }
            }

            if (lastWhitespace > start) {
                // Break at whitespace boundary
                String chunk = trimmed.substring(start, lastWhitespace).trim();
                if (!chunk.isEmpty()) {
                    chunks.add(chunk);
                }
                start = lastWhitespace + 1;
            } else {
                // Unbroken token: hard cut at targetEnd without trimming to preserve token integrity
                String chunk = trimmed.substring(start, targetEnd);
                chunks.add(chunk);
                start = targetEnd;
            }
        }

        return List.copyOf(chunks);
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
