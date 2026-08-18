package com.universe.novel.application.chapter;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.VolumeNotFoundException;
import com.universe.novel.application.exceptions.VolumeNotPublishedException;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.VolumeRepositoryPort;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.Slug;
import com.universe.novel.domain.Volume;
import com.universe.novel.domain.VolumeStatus;
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
class PublishChapterUseCaseTest {

    private static final UUID CHAPTER_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID VOLUME_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333"
            );

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-08-17T10:00:00Z"
            );

    private static final Instant PUBLISHED_AT =
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

    private PublishChapterUseCase
            useCase;

    @BeforeEach
    void setUp() {
        useCase =
                new PublishChapterUseCase(
                        chapterRepositoryPort,
                        volumeRepositoryPort,
                        clockPort
                );
    }

    @Test
    @DisplayName(
            "Xuất bản Chapter khi Volume cha PUBLISHED"
    )
    void shouldPublishChapter() {

        Chapter chapter =
                createDraftChapter(
                        "Nội dung chương."
                );

        Volume volume =
                createPublishedVolume();

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
                volumeRepositoryPort.findByIdForUpdate(
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
                PUBLISHED_AT
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
                        new PublishChapterCommand(
                                CHAPTER_ID,
                                ADMIN_ID
                        )
                );

        assertThat(
                chapter.getStatus()
        ).isEqualTo(
                ChapterStatus.PUBLISHED
        );

        assertThat(
                chapter.getPublishedBy()
        ).isEqualTo(
                ADMIN_ID
        );

        assertThat(
                chapter.getPublishedAt()
        ).isEqualTo(
                PUBLISHED_AT
        );

        assertThat(
                chapter.getAggregateVersion()
        ).isEqualTo(
                2L
        );

        /*
         * Publish không thay content.
         */
        assertThat(
                chapter.getContentVersion()
        ).isEqualTo(
                1L
        );

        assertThat(
                result.status()
        ).isEqualTo(
                "PUBLISHED"
        );

        assertThat(
                result.aggregateVersion()
        ).isEqualTo(
                2L
        );

        verify(
                volumeRepositoryPort
        ).findByIdForUpdate(
                VOLUME_ID
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
            "Từ chối publish khi Chapter không tồn tại"
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
                        command()
                )
        )
                .isInstanceOf(
                        ChapterNotFoundException.class
                );

        verify(
                volumeRepositoryPort,
                never()
        ).findByIdForUpdate(
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
            "Từ chối publish khi Volume cha không tồn tại"
    )
    void shouldRejectMissingVolume() {

        Chapter chapter =
                createDraftChapter(
                        "Nội dung chương."
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
                volumeRepositoryPort.findByIdForUpdate(
                        VOLUME_ID
                )
        ).thenReturn(
                Optional.empty()
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        command()
                )
        )
                .isInstanceOf(
                        VolumeNotFoundException.class
                );

        assertThat(
                chapter.getStatus()
        ).isEqualTo(
                ChapterStatus.DRAFT
        );

        assertThat(
                chapter.getAggregateVersion()
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
            "Từ chối publish khi Volume cha chưa PUBLISHED"
    )
    void shouldRejectDraftParentVolume() {

        Chapter chapter =
                createDraftChapter(
                        "Nội dung chương."
                );

        Volume draftVolume =
                createDraftVolume();

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
                volumeRepositoryPort.findByIdForUpdate(
                        VOLUME_ID
                )
        ).thenReturn(
                Optional.of(
                        draftVolume
                )
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        command()
                )
        )
                .isInstanceOf(
                        VolumeNotPublishedException.class
                )
                .hasMessage(
                        "Không thể xuất bản chương vì tập cha chưa được xuất bản: "
                                + VOLUME_ID
                );

        assertThat(
                chapter.getStatus()
        ).isEqualTo(
                ChapterStatus.DRAFT
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
            "Từ chối publish Chapter khi content rỗng"
    )
    void shouldRejectBlankContent() {

        Chapter chapter =
                createDraftChapter(
                        "   "
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
                volumeRepositoryPort.findByIdForUpdate(
                        VOLUME_ID
                )
        ).thenReturn(
                Optional.of(
                        createPublishedVolume()
                )
        );

        when(
                clockPort.now()
        ).thenReturn(
                PUBLISHED_AT
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        command()
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Chương phải có nội dung trước khi xuất bản."
                );

        assertThat(
                chapter.getStatus()
        ).isEqualTo(
                ChapterStatus.DRAFT
        );

        assertThat(
                chapter.getAggregateVersion()
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
            "Từ chối publish Chapter đã PUBLISHED"
    )
    void shouldRejectAlreadyPublishedChapter() {

        Chapter chapter =
                createDraftChapter(
                        "Nội dung chương."
                );

        chapter.publish(
                ADMIN_ID,
                CREATED_AT.plusSeconds(
                        30
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
                volumeRepositoryPort.findByIdForUpdate(
                        VOLUME_ID
                )
        ).thenReturn(
                Optional.of(
                        createPublishedVolume()
                )
        );

        when(
                clockPort.now()
        ).thenReturn(
                PUBLISHED_AT
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        command()
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Chỉ chương ở trạng thái DRAFT mới được xuất bản."
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
            "Từ chối PublishChapterCommand null"
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
                        "Publish chapter command không được để trống."
                );

        verify(
                chapterRepositoryPort,
                never()
        ).findById(
                any(UUID.class)
        );

        verify(
                volumeRepositoryPort,
                never()
        ).findByIdForUpdate(
                any(UUID.class)
        );
    }

    private PublishChapterCommand command() {
        return new PublishChapterCommand(
                CHAPTER_ID,
                ADMIN_ID
        );
    }

    private Chapter createDraftChapter(
            String content
    ) {
        return Chapter.createDraft(
                CHAPTER_ID,
                VOLUME_ID,
                1,
                1,
                "Chương Một",
                new Slug(
                        "chuong-mot"
                ),
                "Tóm tắt.",
                content,
                ADMIN_ID,
                CREATED_AT
        );
    }

    private Volume createDraftVolume() {
        return Volume.createDraft(
                VOLUME_ID,
                "Quyển Một",
                new Slug(
                        "quyen-mot"
                ),
                "Volume cha.",
                1,
                ADMIN_ID,
                CREATED_AT.minusSeconds(
                        60
                )
        );
    }

    private Volume createPublishedVolume() {

        Volume volume =
                createDraftVolume();

        volume.publish(
                ADMIN_ID,
                CREATED_AT.minusSeconds(
                        30
                )
        );

        assertThat(
                volume.getStatus()
        ).isEqualTo(
                VolumeStatus.PUBLISHED
        );

        return volume;
    }
}