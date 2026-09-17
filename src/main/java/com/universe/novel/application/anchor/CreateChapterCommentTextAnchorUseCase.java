package com.universe.novel.application.anchor;

import com.universe.novel.application.exceptions.ChapterCommentAnchorVersionConflictException;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort.ChapterAnchorDocumentSnapshot;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort.ReaderBlock;
import com.universe.novel.application.ports.ChapterCommentAnchorRepositoryPort;
import com.universe.novel.domain.anchor.ChapterCommentAnchor;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Use case to validate and persist a canonical TEXT_RANGE ChapterCommentAnchor for a Novel chapter.
 *
 * <p>Preserves clean architecture contracts:
 * <ul>
 *   <li>Never trusts client text evidence: selectedText, contextBefore, contextAfter are reconstructed
 *       strictly from the current canonical Reader snapshot;</li>
 *   <li>Enforces that requested contentVersion equals the current Reader snapshot version, throwing
 *       {@link ChapterCommentAnchorVersionConflictException} (409 Conflict) on mismatch;</li>
 *   <li>Validates that blockKey matches exactly one canonical Reader block in the snapshot;</li>
 *   <li>Validates UTF-16 offset boundaries against the block's canonical text length;</li>
 *   <li>Constructs and persists an immutable {@link ChapterCommentAnchor} via {@link ChapterCommentAnchorRepositoryPort}.</li>
 * </ul>
 */
@Service
public class CreateChapterCommentTextAnchorUseCase {

    private final ChapterAnchorResolutionSourcePort resolutionSourcePort;
    private final ChapterCommentAnchorRepositoryPort anchorRepositoryPort;
    private final ClockPort clockPort;

    public CreateChapterCommentTextAnchorUseCase(
            ChapterAnchorResolutionSourcePort resolutionSourcePort,
            ChapterCommentAnchorRepositoryPort anchorRepositoryPort,
            ClockPort clockPort
    ) {
        this.resolutionSourcePort = Objects.requireNonNull(resolutionSourcePort, "ChapterAnchorResolutionSourcePort cannot be null");
        this.anchorRepositoryPort = Objects.requireNonNull(anchorRepositoryPort, "ChapterCommentAnchorRepositoryPort cannot be null");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null");
    }

    @Transactional
    public ChapterCommentAnchor execute(CreateChapterCommentTextAnchorCommand command) {
        Objects.requireNonNull(command, "CreateChapterCommentTextAnchorCommand cannot be null");

        // Load current snapshot exactly once
        ChapterAnchorDocumentSnapshot snapshot = resolutionSourcePort.loadCurrent(command.chapterId());
        Objects.requireNonNull(snapshot, "Chapter anchor document snapshot cannot be null");

        // Validate contentVersion equals current Reader snapshot version
        if (command.contentVersion() != snapshot.contentVersion()) {
            throw new ChapterCommentAnchorVersionConflictException(
                    command.chapterId(),
                    command.contentVersion(),
                    snapshot.contentVersion()
            );
        }

        // Find blockKey in current snapshot. Require EXACTLY ONE matching ReaderBlock.
        List<ReaderBlock> matchingBlocks = snapshot.blocks().stream()
                .filter(b -> command.blockKey().equals(b.blockKey()))
                .toList();

        if (matchingBlocks.isEmpty()) {
            throw new IllegalArgumentException("Block key not found in chapter: " + command.blockKey());
        }
        if (matchingBlocks.size() > 1) {
            throw new IllegalArgumentException("Multiple blocks found matching block key in chapter: " + command.blockKey());
        }

        ReaderBlock block = matchingBlocks.get(0);
        String canonicalBlockText = block.canonicalText();

        // Validate UTF-16 offsets: 0 <= startOffset < endOffset <= canonicalBlockText.length()
        if (command.startOffset() < 0 ||
                command.endOffset() <= command.startOffset() ||
                command.endOffset() > canonicalBlockText.length()) {
            throw new IllegalArgumentException(
                    "Invalid offsets [" + command.startOffset() + ", " + command.endOffset() +
                    "] for canonical text of length " + canonicalBlockText.length()
            );
        }

        // Server derives selectedText and surrounding context (up to 64 UTF-16 code units)
        String selectedText = canonicalBlockText.substring(command.startOffset(), command.endOffset());

        int beforeStart = Math.max(0, command.startOffset() - ChapterCommentAnchor.MAX_CONTEXT_CODE_UNITS);
        String contextBefore = canonicalBlockText.substring(beforeStart, command.startOffset());

        int afterEnd = Math.min(canonicalBlockText.length(), command.endOffset() + ChapterCommentAnchor.MAX_CONTEXT_CODE_UNITS);
        String contextAfter = canonicalBlockText.substring(command.endOffset(), afterEnd);

        Instant createdAt = clockPort.now();

        ChapterCommentAnchor anchor = ChapterCommentAnchor.createTextRange(
                command.rootCommentId(),
                command.chapterId(),
                command.contentVersion(),
                command.blockKey(),
                command.startOffset(),
                command.endOffset(),
                selectedText,
                contextBefore,
                contextAfter,
                createdAt
        );

        return anchorRepositoryPort.save(anchor);
    }
}
