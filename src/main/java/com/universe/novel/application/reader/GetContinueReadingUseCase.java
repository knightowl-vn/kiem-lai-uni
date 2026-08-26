package com.universe.novel.application.reader;

import com.universe.novel.application.ports.ReaderContinueReadingQueryPort;
import com.universe.novel.application.ports.ReaderContinueReadingQueryPort.ReadableChapterDestination;
import com.universe.novel.application.ports.ReadingProgressRepositoryPort;
import com.universe.novel.contracts.dto.reader.ReaderContinueReadingDTO;
import com.universe.novel.domain.reader.UserReadingProgress;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class GetContinueReadingUseCase {

    private final ReadingProgressRepositoryPort readingProgressRepositoryPort;
    private final ReaderContinueReadingQueryPort continueReadingQueryPort;

    public GetContinueReadingUseCase(
            ReadingProgressRepositoryPort readingProgressRepositoryPort,
            ReaderContinueReadingQueryPort continueReadingQueryPort
    ) {
        this.readingProgressRepositoryPort = Objects.requireNonNull(
                readingProgressRepositoryPort,
                "ReadingProgressRepositoryPort không được để trống."
        );
        this.continueReadingQueryPort = Objects.requireNonNull(
                continueReadingQueryPort,
                "ReaderContinueReadingQueryPort không được để trống."
        );
    }

    public Optional<ReaderContinueReadingDTO> execute(
            UUID userId
    ) {
        if (userId == null) {
            return Optional.empty();
        }

        Optional<UserReadingProgress> progressOpt =
                readingProgressRepositoryPort.findByUserId(userId);

        if (progressOpt.isEmpty()) {
            return Optional.empty();
        }

        UserReadingProgress progress = progressOpt.get();
        UUID lastOpenedChapterId = progress.getLastOpenedChapterId();
        int highestReachedChapterNumber = progress.getHighestReachedChapterNumber();

        // 1. Kiểm tra trực tiếp: chương lastOpenedChapterId có đang PUBLISHED và thuộc Volume PUBLISHED hay không
        Optional<ReadableChapterDestination> directChapter =
                continueReadingQueryPort.findPublishedChapterById(lastOpenedChapterId);

        if (directChapter.isPresent()) {
            return Optional.of(
                    toDTO(directChapter.get(), highestReachedChapterNumber)
            );
        }

        // 2. lastOpenedChapter không khả dụng; giải quyết chapter_number cấu trúc của chương đó
        Optional<Integer> structuralNumberOpt =
                continueReadingQueryPort.findChapterNumberById(lastOpenedChapterId);

        if (structuralNumberOpt.isEmpty()) {
            return Optional.empty();
        }

        int targetChapterNumber = structuralNumberOpt.get();

        // 3. Fallback về chương khả dụng gần nhất phía trước (chapter_number < targetChapterNumber)
        Optional<ReadableChapterDestination> fallbackChapter =
                continueReadingQueryPort.findPreviousPublishedChapter(targetChapterNumber);

        // 4. Nếu không có chương phía trước khả dụng, fallback về chương khả dụng gần nhất phía sau (chapter_number > targetChapterNumber)
        if (fallbackChapter.isEmpty()) {
            fallbackChapter =
                    continueReadingQueryPort.findNextPublishedChapter(targetChapterNumber);
        }

        // 5. Nếu không còn chương khả dụng nào trong toàn bộ novel, trả về empty
        if (fallbackChapter.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(
                toDTO(fallbackChapter.get(), highestReachedChapterNumber)
        );
    }

    private ReaderContinueReadingDTO toDTO(
            ReadableChapterDestination destination,
            int highestReachedChapterNumber
    ) {
        return new ReaderContinueReadingDTO(
                destination.chapterId(),
                destination.chapterNumber(),
                destination.title(),
                destination.slug(),
                highestReachedChapterNumber
        );
    }
}
