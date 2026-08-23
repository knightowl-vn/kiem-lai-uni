package com.universe.novel.application.chapter;

import com.universe.novel.application.chapter.revision.ChapterRevisionRecorder;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ChapterSlugAlreadyExistsException;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.VolumeRepositoryPort;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.Slug;
import com.universe.novel.domain.Volume;
import com.universe.novel.domain.revision.ChapterRevisionChangeType;
import com.universe.shared.time.ClockPort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ConcurrentModificationException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpdateDraftChapterUseCaseTest {

    private static final UUID CHAPTER_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID OTHER_CHAPTER_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final UUID VOLUME_ID =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333"
            );

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "44444444-4444-4444-4444-444444444444"
            );

    private static final UUID OTHER_ADMIN_ID =
            UUID.fromString(
                    "55555555-5555-5555-5555-555555555555"
            );

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-08-17T10:00:00Z"
            );

    private static final Instant UPDATED_AT =
            Instant.parse(
                    "2026-08-17T11:00:00Z"
            );

    @Mock
    private ChapterRepositoryPort
            chapterRepositoryPort;

    @Mock
    private VolumeRepositoryPort
            volumeRepositoryPort;

    @Mock
    private ClockPort
            clockPort;

    @Mock
    private ChapterRevisionRecorder
            chapterRevisionRecorder;

    private UpdateDraftChapterUseCase
            useCase;

    @BeforeEach
    void setUp() {
        useCase =
                new UpdateDraftChapterUseCase(
                        chapterRepositoryPort,
                        volumeRepositoryPort,
                        clockPort,
                        chapterRevisionRecorder
                );
    }

    @Test
    @DisplayName(
            "Cập nhật Chapter DRAFT và tăng contentVersion khi content thay đổi"
    )
    void shouldUpdateDraftAndIncreaseContentVersion() {

        Chapter chapter =
                createDraftChapter();

        Volume volume =
                createVolume();

        Slug newSlug =
                new Slug(
                        "quyen-1-chuong-2"
                );

        when(
                chapterRepositoryPort.findById(
                        CHAPTER_ID
                )
        ).thenReturn(
                Optional.of(
                        chapter
                )
        );

        when(
                volumeRepositoryPort.findById(
                        VOLUME_ID
                )
        ).thenReturn(
                Optional.of(
                        volume
                )
        );

        when(
                chapterRepositoryPort
                        .existsByChapterNumber(
                                2
                        )
        ).thenReturn(
                false
        );

        when(
                chapterRepositoryPort.findBySlug(
                        newSlug
                )
        ).thenReturn(
                Optional.empty()
        );

        when(
                clockPort.now()
        ).thenReturn(
                UPDATED_AT
        );

        when(
                chapterRepositoryPort.save(
                        chapter,
                        1L
                )
        ).thenReturn(
                chapter
        );

        ChapterDTO result =
                useCase.execute(
                        new UpdateDraftChapterCommand(
                                CHAPTER_ID,
                                2,
                                "  Chương Một Hoàn Thiện  ",
                                "  Tóm tắt mới.  ",
                                "  Nội dung mới của chương.  ",
                                OTHER_ADMIN_ID
                        )
                );

        assertThat(
                chapter.getChapterNumber()
        ).isEqualTo(
                2
        );

        assertThat(
                chapter.getTitle()
        ).isEqualTo(
                "Chương Một Hoàn Thiện"
        );

        assertThat(
                chapter.getSlug().value()
        ).isEqualTo(
                "quyen-1-chuong-2"
        );

        assertThat(
                chapter.getSummary()
        ).isEqualTo(
                "Tóm tắt mới."
        );

        assertThat(
                chapter.getContent()
        ).isEqualTo(
                "Nội dung mới của chương."
        );

        /*
         * Update Draft không được tự đổi Volume.
         * chapterNumber là thứ tự chương toàn cục.
         */
        assertThat(
                chapter.getVolumeId()
        ).isEqualTo(
                VOLUME_ID
        );

        assertThat(
                chapter.getStatus()
        ).isEqualTo(
                ChapterStatus.DRAFT
        );

        assertThat(
                chapter.getUpdatedBy()
        ).isEqualTo(
                OTHER_ADMIN_ID
        );

        assertThat(
                chapter.getUpdatedAt()
        ).isEqualTo(
                UPDATED_AT
        );

        assertThat(
                chapter.getAggregateVersion()
        ).isEqualTo(
                2L
        );

        assertThat(
                chapter.getContentVersion()
        ).isEqualTo(
                2L
        );

        assertThat(
                result.aggregateVersion()
        ).isEqualTo(
                2L
        );

        assertThat(
                result.contentVersion()
        ).isEqualTo(
                2L
        );

        verify(
                chapterRepositoryPort
        ).save(
                chapter,
                1L
        );

        verify(
                chapterRevisionRecorder
        ).record(
                chapter,
                ChapterRevisionChangeType.UPDATE_DRAFT,
                OTHER_ADMIN_ID,
                null
        );
    }

    @Test
    @DisplayName(
            "Không ghi revision khi lưu Chapter thất bại do xung đột đồng thời"
    )
    void shouldNotRecordRevisionWhenConcurrencyConflictFails() {
        Chapter chapter = createDraftChapter();
        Volume volume = createVolume();

        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(chapter));
        when(volumeRepositoryPort.findById(VOLUME_ID)).thenReturn(Optional.of(volume));
        when(clockPort.now()).thenReturn(UPDATED_AT);
        when(chapterRepositoryPort.save(chapter, 1L))
                .thenThrow(new ConcurrentModificationException("Optimistic lock error"));

        assertThatThrownBy(() -> useCase.execute(updateCommand()))
                .isInstanceOf(ConcurrentModificationException.class)
                .hasMessage("Optimistic lock error");

        verify(chapterRevisionRecorder, never()).record(any(), any(), any(), any());
    }

    @Test
    @DisplayName(
            "Không tăng contentVersion khi chỉ đổi metadata"
    )
    void shouldNotIncreaseContentVersionWhenContentUnchanged() {

        Chapter chapter =
                createDraftChapter();

        Volume volume =
                createVolume();

        Slug newSlug =
                new Slug(
                        "quyen-1-chuong-5"
                );

        when(
                chapterRepositoryPort.findById(
                        CHAPTER_ID
                )
        ).thenReturn(
                Optional.of(
                        chapter
                )
        );

        when(
                volumeRepositoryPort.findById(
                        VOLUME_ID
                )
        ).thenReturn(
                Optional.of(
                        volume
                )
        );

        when(
                chapterRepositoryPort
                        .existsByChapterNumber(
                                5
                        )
        ).thenReturn(
                false
        );

        when(
                chapterRepositoryPort.findBySlug(
                        newSlug
                )
        ).thenReturn(
                Optional.empty()
        );

        when(
                clockPort.now()
        ).thenReturn(
                UPDATED_AT
        );

        when(
                chapterRepositoryPort.save(
                        chapter,
                        1L
                )
        ).thenReturn(
                chapter
        );

        ChapterDTO result =
                useCase.execute(
                        new UpdateDraftChapterCommand(
                                CHAPTER_ID,
                                5,
                                "Tên chương mới",
                                "Tóm tắt metadata mới.",
                                "Nội dung ban đầu.",
                                OTHER_ADMIN_ID
                        )
                );

        assertThat(
                chapter.getAggregateVersion()
        ).isEqualTo(
                2L
        );

        assertThat(
                chapter.getChapterNumber()
        ).isEqualTo(
                5
        );

        assertThat(
                chapter.getSlug().value()
        ).isEqualTo(
                "quyen-1-chuong-5"
        );

        assertThat(
                chapter.getContentVersion()
        ).isEqualTo(
                1L
        );

        assertThat(
                result.contentVersion()
        ).isEqualTo(
                1L
        );

        verify(
                chapterRepositoryPort
        ).save(
                chapter,
                1L
        );
    }

    @Test
    @DisplayName(
            "Content giống nhau sau normalize không tăng contentVersion"
    )
    void shouldNotIncreaseContentVersionWhenNormalizedContentUnchanged() {

        Chapter chapter =
                createDraftChapter();

        Volume volume =
                createVolume();

        when(
                chapterRepositoryPort.findById(
                        CHAPTER_ID
                )
        ).thenReturn(
                Optional.of(
                        chapter
                )
        );

        when(
                volumeRepositoryPort.findById(
                        VOLUME_ID
                )
        ).thenReturn(
                Optional.of(
                        volume
                )
        );

        when(
                clockPort.now()
        ).thenReturn(
                UPDATED_AT
        );

        ChapterDTO result =
                useCase.execute(
                        new UpdateDraftChapterCommand(
                                CHAPTER_ID,
                                1,
                                "Chương Một",
                                "Tóm tắt chương.",
                                "   Nội dung ban đầu.   ",
                                OTHER_ADMIN_ID
                        )
                );

        assertThat(
                chapter.getContent()
        ).isEqualTo(
                "Nội dung ban đầu."
        );

        assertThat(
                chapter.getUpdatedBy()
        ).isEqualTo(
                ADMIN_ID
        );

        assertThat(
                chapter.getUpdatedAt()
        ).isEqualTo(
                CREATED_AT
        );

        assertThat(
                chapter.getAggregateVersion()
        ).isEqualTo(
                1L
        );

        assertThat(
                chapter.getContentVersion()
        ).isEqualTo(
                1L
        );

        assertThat(
                result.contentVersion()
        ).isEqualTo(
                1L
        );

        verify(
                chapterRepositoryPort,
                never()
        ).save(
                any(Chapter.class),
                anyLong()
        );
    }

    @Test
    @DisplayName(
            "Cho phép giữ nguyên slug của chính Chapter"
    )
    void shouldAllowCurrentChapterSlug() {

        Chapter chapter =
                createDraftChapter();

        Volume volume =
                createVolume();

        when(
                chapterRepositoryPort.findById(
                        CHAPTER_ID
                )
        ).thenReturn(
                Optional.of(
                        chapter
                )
        );

        when(
                volumeRepositoryPort.findById(
                        VOLUME_ID
                )
        ).thenReturn(
                Optional.of(
                        volume
                )
        );

        when(
                clockPort.now()
        ).thenReturn(
                UPDATED_AT
        );

        when(
                chapterRepositoryPort.save(
                        chapter,
                        1L
                )
        ).thenReturn(
                chapter
        );

        ChapterDTO result =
                useCase.execute(
                        updateCommand()
                );

        assertThat(
                result.slug()
        ).isEqualTo(
                "quyen-1-chuong-1"
        );

        verify(
                chapterRepositoryPort
        ).save(
                chapter,
                1L
        );
    }

    @Test
    @DisplayName(
            "Từ chối slug thuộc Chapter khác và không mutate Aggregate"
    )
    void shouldRejectSlugOwnedByAnotherChapter() {

        Chapter chapter =
                createDraftChapter();

        Volume volume =
                createVolume();

        Chapter otherChapter =
                Chapter.createDraft(
                        OTHER_CHAPTER_ID,
                        VOLUME_ID,
                        99,
                        "Chương Hai",
                        new Slug(
                                "quyen-1-chuong-2"
                        ),
                        "Tóm tắt chương hai.",
                        "Nội dung chương hai.",
                        ADMIN_ID,
                        CREATED_AT
                );

        Slug duplicateSlug =
                new Slug(
                        "quyen-1-chuong-2"
                );

        when(
                chapterRepositoryPort.findById(
                        CHAPTER_ID
                )
        ).thenReturn(
                Optional.of(
                        chapter
                )
        );

        when(
                volumeRepositoryPort.findById(
                        VOLUME_ID
                )
        ).thenReturn(
                Optional.of(
                        volume
                )
        );

        when(
                chapterRepositoryPort
                        .existsByChapterNumber(
                                2
                        )
        ).thenReturn(
                false
        );

        when(
                chapterRepositoryPort.findBySlug(
                        duplicateSlug
                )
        ).thenReturn(
                Optional.of(
                        otherChapter
                )
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        new UpdateDraftChapterCommand(
                                CHAPTER_ID,
                                2,
                                "Tên không được lưu",
                                "Summary không được lưu",
                                "Content không được lưu",
                                OTHER_ADMIN_ID
                        )
                )
        )
                .isInstanceOf(
                        ChapterSlugAlreadyExistsException.class
                )
                .hasMessage(
                        "Slug của chương đã tồn tại: quyen-1-chuong-2"
                );

        assertThat(
                chapter.getChapterNumber()
        ).isEqualTo(
                1
        );

        assertThat(
                chapter.getTitle()
        ).isEqualTo(
                "Chương Một"
        );

        assertThat(
                chapter.getSlug().value()
        ).isEqualTo(
                "quyen-1-chuong-1"
        );

        assertThat(
                chapter.getContent()
        ).isEqualTo(
                "Nội dung ban đầu."
        );

        assertThat(
                chapter.getAggregateVersion()
        ).isEqualTo(
                1L
        );

        assertThat(
                chapter.getContentVersion()
        ).isEqualTo(
                1L
        );

        verify(
                clockPort,
                never()
        ).now();

        verify(
                chapterRepositoryPort,
                never()
        ).save(
                any(Chapter.class),
                anyLong()
        );
    }

    @Test
    @DisplayName(
            "Từ chối Chapter không tồn tại"
    )
    void shouldRejectMissingChapter() {

        when(
                chapterRepositoryPort.findById(
                        CHAPTER_ID
                )
        ).thenReturn(
                Optional.empty()
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        updateCommand()
                )
        )
                .isInstanceOf(
                        ChapterNotFoundException.class
                )
                .hasMessage(
                        "Không tìm thấy chương: "
                                + CHAPTER_ID
                );

        verify(
                chapterRepositoryPort,
                never()
        ).findBySlug(
                any(Slug.class)
        );

        verify(
                clockPort,
                never()
        ).now();

        verify(
                chapterRepositoryPort,
                never()
        ).save(
                any(Chapter.class),
                anyLong()
        );
    }

    @Test
    @DisplayName(
            "Từ chối chỉnh sửa Chapter đã PUBLISHED"
    )
    void shouldRejectPublishedChapter() {

        Chapter chapter =
                createDraftChapter();

        chapter.publish(
                ADMIN_ID,
                CREATED_AT.plusSeconds(
                        60
                )
        );

        long aggregateVersionBefore =
                chapter.getAggregateVersion();

        long contentVersionBefore =
                chapter.getContentVersion();

        when(
                chapterRepositoryPort.findById(
                        CHAPTER_ID
                )
        ).thenReturn(
                Optional.of(
                        chapter
                )
        );

        when(
                volumeRepositoryPort.findById(
                        VOLUME_ID
                )
        ).thenReturn(
                Optional.of(
                        createVolume()
                )
        );

        when(
                clockPort.now()
        ).thenReturn(
                UPDATED_AT
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        updateCommand()
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Chỉ được cập nhật nội dung khi chương còn là bản nháp."
                );

        assertThat(
                chapter.getStatus()
        ).isEqualTo(
                ChapterStatus.PUBLISHED
        );

        assertThat(
                chapter.getAggregateVersion()
        ).isEqualTo(
                aggregateVersionBefore
        );

        assertThat(
                chapter.getContentVersion()
        ).isEqualTo(
                contentVersionBefore
        );

        verify(
                chapterRepositoryPort,
                never()
        ).save(
                any(Chapter.class),
                anyLong()
        );
    }

    @Test
    @DisplayName(
            "Từ chối UpdateDraftChapterCommand null"
    )
    void shouldRejectNullCommand() {

        assertThatThrownBy(() ->
                useCase.execute(
                        null
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "Update draft chapter command không được để trống."
                );

        verify(
                chapterRepositoryPort,
                never()
        ).findById(
                any(UUID.class)
        );

        verify(
                chapterRepositoryPort,
                never()
        ).save(
                any(Chapter.class),
                anyLong()
        );
    }

    private Chapter createDraftChapter() {
        return Chapter.createDraft(
                CHAPTER_ID,
                VOLUME_ID,
                1,
                "Chương Một",
                new Slug(
                        "quyen-1-chuong-1"
                ),
                "Tóm tắt chương.",
                "Nội dung ban đầu.",
                ADMIN_ID,
                CREATED_AT
        );
    }

    private Volume createVolume() {
        return Volume.createDraft(
                VOLUME_ID,
                "Quyển Một",
                new Slug(
                        "quyen-mot"
                ),
                "Volume test.",
                1,
                ADMIN_ID,
                CREATED_AT
        );
    }

    private UpdateDraftChapterCommand updateCommand() {
        return new UpdateDraftChapterCommand(
                CHAPTER_ID,
                1,
                "Chương Một chỉnh sửa",
                "Tóm tắt mới.",
                "Nội dung ban đầu.",
                OTHER_ADMIN_ID
        );
    }
}