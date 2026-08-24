package com.universe.novel.application.chapter;

import com.universe.novel.application.chapter.revision.ChapterRevisionRecorder;
import com.universe.novel.application.exceptions.ChapterNumberAlreadyExistsException;
import com.universe.novel.application.exceptions.ChapterSlugAlreadyExistsException;
import com.universe.novel.application.exceptions.VolumeNotFoundException;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.VolumeRepositoryPort;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.Slug;
import com.universe.novel.domain.Volume;
import com.universe.novel.domain.revision.ChapterRevisionChangeType;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateChapterUseCaseTest {

    private static final UUID VOLUME_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID CHAPTER_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333"
            );

    private static final Instant NOW =
            Instant.parse(
                    "2026-08-17T12:00:00Z"
            );

    @Mock
    private ChapterRepositoryPort
            chapterRepositoryPort;

    @Mock
    private VolumeRepositoryPort
            volumeRepositoryPort;

    @Mock
    private IdGeneratorPort
            idGeneratorPort;

    @Mock
    private ClockPort
            clockPort;

    @Mock
    private ChapterRevisionRecorder
            chapterRevisionRecorder;

    private CreateChapterUseCase
            useCase;

    @BeforeEach
    void setUp() {
        useCase =
                new CreateChapterUseCase(
                        chapterRepositoryPort,
                        volumeRepositoryPort,
                        idGeneratorPort,
                        clockPort,
                        chapterRevisionRecorder
                );
    }

    @Test
    @DisplayName(
            "Tạo Chapter DRAFT thành công"
    )
    void shouldCreateDraftChapter() {

        Volume volume =
                createVolume();

        Slug slug =
                new Slug(
                        "quyen-1-chuong-1"
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
                                1
                        )
        ).thenReturn(
                false
        );

        when(
                chapterRepositoryPort.existsBySlug(
                        slug
                )
        ).thenReturn(
                false
        );

        when(
                idGeneratorPort.generate()
        ).thenReturn(
                CHAPTER_ID
        );

        when(
                clockPort.now()
        ).thenReturn(
                NOW
        );

        when(
                chapterRepositoryPort.save(
                        any(Chapter.class),
                        anyLong()
                )
        ).thenAnswer(
                invocation ->
                        invocation.getArgument(0)
        );

        ChapterDTO result =
                useCase.execute(
                        createCommand()
                );

        ArgumentCaptor<Chapter> chapterCaptor =
                ArgumentCaptor.forClass(
                        Chapter.class
                );

        verify(
                chapterRepositoryPort
        ).save(
                chapterCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(
                        0L
                )
        );

        Chapter saved =
                chapterCaptor.getValue();

        assertThat(
                saved.getId()
        ).isEqualTo(
                CHAPTER_ID
        );

        assertThat(
                saved.getVolumeId()
        ).isEqualTo(
                VOLUME_ID
        );

        assertThat(
                saved.getChapterNumber()
        ).isEqualTo(
                1
        );        

        assertThat(
                saved.getTitle()
        ).isEqualTo(
                "Thiếu niên"
        );

        assertThat(
                saved.getSlug().value()
        ).isEqualTo(
                "quyen-1-chuong-1"
        );

        assertThat(
                saved.getSummary()
        ).isEqualTo(
                "Mở đầu câu chuyện."
        );

        assertThat(
                saved.getContent()
        ).isEqualTo(
                "Nội dung chương đầu tiên."
        );

        assertThat(
                saved.getStatus()
        ).isEqualTo(
                ChapterStatus.DRAFT
        );

        assertThat(
                saved.getCreatedBy()
        ).isEqualTo(
                ADMIN_ID
        );

        assertThat(
                saved.getUpdatedBy()
        ).isEqualTo(
                ADMIN_ID
        );

        assertThat(
                saved.getCreatedAt()
        ).isEqualTo(
                NOW
        );

        assertThat(
                saved.getUpdatedAt()
        ).isEqualTo(
                NOW
        );

        assertThat(
                saved.getAggregateVersion()
        ).isEqualTo(
                1L
        );

        assertThat(
                saved.getContentVersion()
        ).isEqualTo(
                1L
        );

        assertThat(
                result.status()
        ).isEqualTo(
                "DRAFT"
        );

        assertThat(
                result.aggregateVersion()
        ).isEqualTo(
                1L
        );

        assertThat(
                result.contentVersion()
        ).isEqualTo(
                1L
        );

        verify(
                chapterRevisionRecorder
        ).record(
                saved,
                ChapterRevisionChangeType.CREATE_DRAFT,
                ADMIN_ID,
                null
        );
    }

    @Test
    @DisplayName(
            "Không ghi revision khi lưu Chapter thất bại"
    )
    void shouldNotRecordRevisionWhenChapterSaveFails() {
        Volume volume = createVolume();

        when(volumeRepositoryPort.findById(VOLUME_ID)).thenReturn(Optional.of(volume));
        when(chapterRepositoryPort.existsByChapterNumber(1)).thenReturn(false);
        when(chapterRepositoryPort.existsBySlug(new Slug("quyen-1-chuong-1"))).thenReturn(false);
        when(idGeneratorPort.generate()).thenReturn(CHAPTER_ID);
        when(clockPort.now()).thenReturn(NOW);
        when(chapterRepositoryPort.save(any(Chapter.class), anyLong()))
                .thenThrow(new RuntimeException("Database error"));

        assertThatThrownBy(() -> useCase.execute(createCommand()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Database error");

        verify(chapterRevisionRecorder, never()).record(any(), any(), any(), any());
    }

    @Test
    @DisplayName(
            "Cho phép tạo Chapter DRAFT với content rỗng"
    )
    void shouldAllowBlankContentForDraftChapter() {

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
                chapterRepositoryPort
                        .existsByChapterNumber(
                                1
                        )
        ).thenReturn(
                false
        );

        when(
                chapterRepositoryPort.existsBySlug(
                        new Slug(
                                "quyen-1-chuong-1"
                        )
                )
        ).thenReturn(
                false
        );
        
        when(
                idGeneratorPort.generate()
        ).thenReturn(
                CHAPTER_ID
        );

        when(
                clockPort.now()
        ).thenReturn(
                NOW
        );

        when(
                chapterRepositoryPort.save(
                        any(Chapter.class),
                        anyLong()
                )
        ).thenAnswer(
                invocation ->
                        invocation.getArgument(0)
        );

        ChapterDTO result =
                useCase.execute(
                        new CreateChapterCommand(
                                VOLUME_ID,
                                1,
                                "Thiếu niên",
                                "Mở đầu.",
                                "   ",
                                ADMIN_ID
                        )
                );

        assertThat(
                result.content()
        ).isEmpty();

        assertThat(
                result.status()
        ).isEqualTo(
                "DRAFT"
        );

        assertThat(
                result.contentVersion()
        ).isEqualTo(
                1L
        );
    }

    @Test
    @DisplayName(
            "Từ chối tạo Chapter khi Volume không tồn tại"
    )
    void shouldRejectMissingVolume() {

        when(
                volumeRepositoryPort.findById(
                        VOLUME_ID
                )
        ).thenReturn(
                Optional.empty()
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        createCommand()
                )
        )
                .isInstanceOf(
                        VolumeNotFoundException.class
                )
                .hasMessage(
                        "Không tìm thấy tập: "
                                + VOLUME_ID
                );

        verify(
                chapterRepositoryPort,
                never()
        ).existsByChapterNumber(
                anyInt()
        );

        verify(
                chapterRepositoryPort,
                never()
        ).existsBySlug(
                any(Slug.class)
        );        

        verify(
                idGeneratorPort,
                never()
        ).generate();

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
            "Từ chối tạo Chapter khi số chương đã tồn tại"
    )
    void shouldRejectDuplicateChapterNumber() {

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
                chapterRepositoryPort
                        .existsByChapterNumber(
                                1
                        )
        ).thenReturn(
                true
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        createCommand()
                )
        )
                .isInstanceOf(
                        ChapterNumberAlreadyExistsException.class
                )
                .hasMessage(
                        "Số chương đã tồn tại: 1"
                );

        verify(
                chapterRepositoryPort,
                never()
        ).existsBySlug(
                any(Slug.class)
        );
        
        verify(
                idGeneratorPort,
                never()
        ).generate();

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
            "Từ chối tạo Chapter khi slug đã tồn tại"
    )
    void shouldRejectDuplicateSlug() {

        Slug slug =
                new Slug(
                        "quyen-1-chuong-1"
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
                chapterRepositoryPort
                        .existsByChapterNumber(
                                1
                        )
        ).thenReturn(
                false
        );

        when(
                chapterRepositoryPort.existsBySlug(
                        slug
                )
        ).thenReturn(
                true
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        createCommand()
                )
        )
                .isInstanceOf(
                        ChapterSlugAlreadyExistsException.class
                )
                .hasMessage(
                        "Slug của chương đã tồn tại: quyen-1-chuong-1"
                );
        
        verify(
                idGeneratorPort,
                never()
        ).generate();

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
    	    "Cho phép tạo Chapter với số chương toàn cục lớn"
    	)
    	void shouldCreateLargeGlobalChapterNumbers() {
    	assertCreatedChapterNumber(
                1500
        );

    	assertCreatedChapterNumber(
                1600
        );

    	assertCreatedChapterNumber(
                1527
        );
    }

    @Test
    @DisplayName(
            "Từ chối số chương 1266 đã tồn tại ở Volume khác"
    )
    void shouldRejectDuplicateGlobalChapterNumberAcrossVolumes() {

        UUID otherVolumeId =
                UUID.fromString(
                        "99999999-9999-9999-9999-999999999999"
                );

        when(
                volumeRepositoryPort.findById(
                        otherVolumeId
                )
        ).thenReturn(
                Optional.of(
                        Volume.createDraft(
                                otherVolumeId,
                                "Quyển Hai",
                                new Slug(
                                        "quyen-hai"
                                ),
                                "Volume khác.",
                                2,
                                ADMIN_ID,
                                NOW.minusSeconds(
                                        60
                                )
                        )
                )
        );

        when(
                chapterRepositoryPort
                        .existsByChapterNumber(
                                1266
                        )
        ).thenReturn(
                true
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        new CreateChapterCommand(
                                otherVolumeId,
                                1266,
                                "Chương 1266",
                                "Tóm tắt.",
                                "Nội dung.",
                                ADMIN_ID
                        )
                )
        )
                .isInstanceOf(
                        ChapterNumberAlreadyExistsException.class
                )
                .hasMessage(
                        "Số chương đã tồn tại: 1266"
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
            "Từ chối tạo Chapter khi chapterNumber null"
    )
    void shouldRejectNullChapterNumber() {

        CreateChapterCommand command =
                new CreateChapterCommand(
                        VOLUME_ID,
                        null,
                        "Chương Một",
                        "Tóm tắt.",
                        "Nội dung.",
                        ADMIN_ID
                );

        assertThatThrownBy(() ->
                useCase.execute(
                        command
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "Số chương không được để trống."
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
            "Từ chối CreateChapterCommand null"
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
                        "Create chapter command không được để trống."
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

    private CreateChapterCommand createCommand() {
        return new CreateChapterCommand(
                VOLUME_ID,
                1,
                "  Thiếu niên  ",
                "  Mở đầu câu chuyện.  ",
                "  Nội dung chương đầu tiên.  ",
                ADMIN_ID
        );
    }

    private void assertCreatedChapterNumber(
            int chapterNumber
    ) {
        Slug slug =
                new Slug(
                        "quyen-1-chuong-"
                                + chapterNumber
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
                chapterRepositoryPort
                        .existsByChapterNumber(
                                chapterNumber
                        )
        ).thenReturn(
                false
        );

        when(
                chapterRepositoryPort.existsBySlug(
                        slug
                )
        ).thenReturn(
                false
        );
        
        when(
                idGeneratorPort.generate()
        ).thenReturn(
                CHAPTER_ID
        );

        when(
                clockPort.now()
        ).thenReturn(
                NOW
        );

        when(
                chapterRepositoryPort.save(
                        any(Chapter.class),
                        anyLong()
                )
        ).thenAnswer(
                invocation ->
                        invocation.getArgument(0)
        );

        useCase.execute(
                new CreateChapterCommand(
                        VOLUME_ID,
                        chapterNumber,
                        "Chương "
                                + chapterNumber,
                        "Tóm tắt.",
                        "Nội dung.",
                        ADMIN_ID
                )
        );

        ArgumentCaptor<Chapter> chapterCaptor =
                ArgumentCaptor.forClass(
                        Chapter.class
                );

        verify(
                chapterRepositoryPort
        ).save(
                chapterCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(
                        0L
                )
        );

        Chapter saved =
                chapterCaptor.getValue();

        assertThat(
                saved.getChapterNumber()
        ).isEqualTo(
                chapterNumber
        );
   
        assertThat(
                saved.getSlug().value()
        ).isEqualTo(
                slug.value()
        );

        org.mockito.Mockito.reset(
                chapterRepositoryPort,
                volumeRepositoryPort,
                idGeneratorPort,
                clockPort
        );
    }

    private Volume createVolume() {
        return Volume.createDraft(
                VOLUME_ID,
                "Quyển Một",
                new Slug(
                        "quyen-mot"
                ),
                "Mở đầu hành trình.",
                1,
                ADMIN_ID,
                NOW.minusSeconds(
                        60
                )
        );
    }
}
