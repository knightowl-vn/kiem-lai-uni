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
 * Use case to validate and persist a canonical BLOCK ChapterCommentAnchor for a Novel chapter.
 *
 * <p>Preserves clean architecture contracts:
 * <ul>
 *   <li>Never trusts client text evidence: canonical block text is reconstructed strictly from the current
 *       canonical Reader snapshot;</li>
 *   <li>Enforces that requested contentVersion equals the current Reader snapshot version, throwing
 *       {@link ChapterCommentAnchorVersionConflictException} (409 Conflict) on mismatch;</li>
 *   <li>Validates that blockKey matches exactly one canonical Reader block in the snapshot;</li>
 *   <li>Constructs and persists an immutable {@link ChapterCommentAnchor} via {@link ChapterCommentAnchorRepositoryPort}.</li>
 * </ul>
 */
@Service
public class CreateChapterCommentBlockAnchorUseCase {

    private final ChapterAnchorResolutionSourcePort resolutionSourcePort;
    private final ChapterCommentAnchorRepositoryPort anchorRepositoryPort;
    private final ClockPort clockPort;

    public CreateChapterCommentBlockAnchorUseCase(
            ChapterAnchorResolutionSourcePort resolutionSourcePort,
            ChapterCommentAnchorRepositoryPort anchorRepositoryPort,
            ClockPort clockPort
    ) {
        this.resolutionSourcePort = Objects.requireNonNull(resolutionSourcePort, "ChapterAnchorResolutionSourcePort cannot be null");
        this.anchorRepositoryPort = Objects.requireNonNull(anchorRepositoryPort, "ChapterCommentAnchorRepositoryPort cannot be null");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null");
    }

    @Transactional
    public ChapterCommentAnchor execute(CreateChapterCommentBlockAnchorCommand command) {
        Objects.requireNonNull(command, "CreateChapterCommentBlockAnchorCommand cannot be null");

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
        Instant createdAt = clockPort.now();

        ChapterCommentAnchor anchor = ChapterCommentAnchor.createBlock(
                command.rootCommentId(),
                command.chapterId(),
                command.contentVersion(),
                command.blockKey(),
                canonicalBlockText,
                createdAt
        );

        return anchorRepositoryPort.save(anchor);
    }
}
