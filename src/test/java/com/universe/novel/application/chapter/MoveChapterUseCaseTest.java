package com.universe.novel.application.chapter;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.VolumeNotFoundException;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.VolumeRepositoryPort;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.Slug;
import com.universe.novel.domain.Volume;
import com.universe.shared.time.ClockPort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
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
class MoveChapterUseCaseTest {

    private static final UUID CHAPTER_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID SOURCE_VOLUME_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final UUID TARGET_VOLUME_ID =
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

    private static final Instant MOVED_AT =
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

    private MoveChapterUseCase
            useCase;

    @BeforeEach
    void setUp() {
        useCase =
                new MoveChapterUseCase(
                        chapterRepositoryPort,
                        volumeRepositoryPort,
                        clockPort
                );
    }

    @Test
    @DisplayName(
            "Di chuyển Chapter DRAFT sang Volume khác thành công"
    )
    void shouldMoveDraftChapterToAnotherVolume() {

        Chapter chapter =
                createDraftChapter();

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
                        TARGET_VOLUME_ID
                )
        ).thenReturn(
                Optional.of(
                        createTargetVolume()
                )
        );

        when(
                chapterRepositoryPort.findBySlug(
                        new Slug(
                                "quyen-2-chuong-1"
                        )
                )
        ).thenReturn(
                Optional.empty()
        );

        when(
                clockPort.now()
        ).thenReturn(
                MOVED_AT
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
                        new MoveChapterCommand(
                                CHAPTER_ID,
                                TARGET_VOLUME_ID,
                                OTHER_ADMIN_ID
                        )
                );

        assertThat(
                chapter.getVolumeId()
        ).isEqualTo(
                TARGET_VOLUME_ID
        );

        assertThat(
                chapter.getSlug().value()
        ).isEqualTo(
                "quyen-2-chuong-1"
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
                MOVED_AT
        );

        assertThat(
                chapter.getAggregateVersion()
        ).isEqualTo(
                2L
        );

        /*
         * Move không thay đổi content.
         */
        assertThat(
                chapter.getContentVersion()
        ).isEqualTo(
                1L
        );

        assertThat(
                result.volumeId()
        ).isEqualTo(
                TARGET_VOLUME_ID
        );

        assertThat(
                result.aggregateVersion()
        ).isEqualTo(
                2L
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
    	    "Di chuyển Chapter 1266 sang Volume khác vẫn giữ nguyên số chương"
    	)
    	void shouldKeepChapterNumberWhenMovingToAnotherVolume() {

        Chapter chapter =
                Chapter.createDraft(
                        CHAPTER_ID,
                        SOURCE_VOLUME_ID,
                        1266,
                        "Chương 1266",
                        new Slug(
                                "quyen-1-chuong-1266"
                        ),
                        "Tóm tắt.",
                        "Nội dung chương.",
                        ADMIN_ID,
                        CREATED_AT
                );

        Volume targetVolume =
                Volume.createDraft(
                        TARGET_VOLUME_ID,
                        "Quyển Mười Ba",
                        new Slug(
                                "quyen-muoi-ba"
                        ),
                        "Volume 13.",
                        13,
                        ADMIN_ID,
                        CREATED_AT
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
                        TARGET_VOLUME_ID
                )
        ).thenReturn(
                Optional.of(
                        targetVolume
                )
        );

        when(
                chapterRepositoryPort.findBySlug(
                        new Slug(
                                "quyen-13-chuong-1266"
                        )
                )
        ).thenReturn(
                Optional.empty()
        );

        when(
                clockPort.now()
        ).thenReturn(
                MOVED_AT
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
                        new MoveChapterCommand(
                                CHAPTER_ID,
                                TARGET_VOLUME_ID,
                                OTHER_ADMIN_ID
                        )
                );

        assertThat(
                chapter.getVolumeId()
        ).isEqualTo(
                TARGET_VOLUME_ID
        );

        assertThat(
                chapter.getChapterNumber()
        ).isEqualTo(
                1266
        );

        assertThat(
                chapter.getSlug().value()
        ).isEqualTo(
                "quyen-13-chuong-1266"
        );

        assertThat(
                chapter.getAggregateVersion()
        ).isEqualTo(
                2L
        );

        assertThat(
                chapter.getContentVersion()
        ).isEqualTo(
                1L
        );

        assertThat(
                result.chapterNumber()
        ).isEqualTo(
                1266
        );

        assertThat(
                result.slug()
        ).isEqualTo(
                "quyen-13-chuong-1266"
        );
    }

    @Test
    @DisplayName(
            "Từ chối khi Chapter không tồn tại"
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
                        moveCommand()
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
                volumeRepositoryPort,
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

    @Test
    @DisplayName(
            "Từ chối khi Volume đích không tồn tại"
    )
    void shouldRejectMissingTargetVolume() {

        Chapter chapter =
                createDraftChapter();

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
                        TARGET_VOLUME_ID
                )
        ).thenReturn(
                Optional.empty()
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        moveCommand()
                )
        )
                .isInstanceOf(
                        VolumeNotFoundException.class
                )
                .hasMessage(
                        "Không tìm thấy tập: "
                                + TARGET_VOLUME_ID
                );

        assertThat(
                chapter.getVolumeId()
        ).isEqualTo(
                SOURCE_VOLUME_ID
        );

        assertThat(
                chapter.getAggregateVersion()
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
            "Từ chối Move khi Volume đích chính là Volume hiện tại"
    )
    void shouldRejectMovingInsideSameVolume() {

        Chapter chapter =
                createDraftChapter();

        when(
                chapterRepositoryPort.findById(
                        CHAPTER_ID
                )
        ).thenReturn(
                Optional.of(
                        chapter
                )
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        new MoveChapterCommand(
                                CHAPTER_ID,
                                SOURCE_VOLUME_ID,
                                OTHER_ADMIN_ID
                        )
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Chapter đã thuộc Volume đích."
                );

        verify(
                volumeRepositoryPort,
                never()
        ).findById(
                any(UUID.class)
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
            "Từ chối di chuyển Chapter đã PUBLISHED"
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

        long versionBefore =
                chapter.getAggregateVersion();

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
                        TARGET_VOLUME_ID
                )
        ).thenReturn(
                Optional.of(
                        createTargetVolume()
                )
        );

        when(
                clockPort.now()
        ).thenReturn(
                MOVED_AT
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        moveCommand()
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Chỉ được di chuyển chương khi còn là bản nháp."
                );

        assertThat(
                chapter.getVolumeId()
        ).isEqualTo(
                SOURCE_VOLUME_ID
        );

        assertThat(
                chapter.getAggregateVersion()
        ).isEqualTo(
                versionBefore
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
            "Từ chối MoveChapterCommand null"
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
                        "Move chapter command không được để trống."
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

    private MoveChapterCommand moveCommand() {
        return new MoveChapterCommand(
                CHAPTER_ID,
                TARGET_VOLUME_ID,
                OTHER_ADMIN_ID
        );
    }

    private Chapter createDraftChapter() {
        return Chapter.createDraft(
                CHAPTER_ID,
                SOURCE_VOLUME_ID,
                1,
                "Chương Một",
                new Slug(
                        "quyen-1-chuong-1"
                ),
                "Tóm tắt.",
                "Nội dung chương.",
                ADMIN_ID,
                CREATED_AT
        );
    }

    private Volume createTargetVolume() {
        return Volume.createDraft(
                TARGET_VOLUME_ID,
                "Quyển Hai",
                new Slug(
                        "quyen-hai"
                ),
                "Volume đích.",
                2,
                ADMIN_ID,
                CREATED_AT
        );
    }
}