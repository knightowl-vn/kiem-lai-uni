package com.universe.novel.application.chapter.revision;

import com.universe.novel.application.ports.ChapterRevisionRepositoryPort;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.Slug;
import com.universe.novel.domain.revision.ChapterRevision;
import com.universe.novel.domain.revision.ChapterRevisionChangeType;
import com.universe.shared.id.IdGeneratorPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChapterRevisionRecorderTest {

    private ChapterRevisionRepositoryPort chapterRevisionRepositoryPort;
    private IdGeneratorPort idGeneratorPort;
    private ChapterRevisionRecorder recorder;

    @BeforeEach
    void setUp() {
        chapterRevisionRepositoryPort = mock(ChapterRevisionRepositoryPort.class);
        idGeneratorPort = mock(IdGeneratorPort.class);
        recorder = new ChapterRevisionRecorder(chapterRevisionRepositoryPort, idGeneratorPort);
    }

    @Test
    void shouldRecordRevisionSnapshotSuccessfully() {
        UUID revisionId = UUID.randomUUID();
        UUID chapterId = UUID.randomUUID();
        UUID volumeId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Instant now = Instant.now();

        when(idGeneratorPort.generate()).thenReturn(revisionId);

        Chapter chapter = Chapter.createDraft(
                chapterId,
                volumeId,
                1,
                "Chương 1: Mở đầu",
                new Slug("quyen-1-chuong-1"),
                "Tóm tắt",
                "Nội dung truyện",
                actorId,
                now
        );

        ChapterRevision result = recorder.record(
                chapter,
                ChapterRevisionChangeType.CREATE_DRAFT,
                actorId,
                "Khởi tạo chương nháp"
        );

        ArgumentCaptor<ChapterRevision> captor = ArgumentCaptor.forClass(ChapterRevision.class);
        verify(chapterRevisionRepositoryPort).save(captor.capture());

        ChapterRevision captured = captor.getValue();
        assertThat(captured.id()).isEqualTo(revisionId);
        assertThat(captured.chapterId()).isEqualTo(chapterId);
        assertThat(captured.volumeId()).isEqualTo(volumeId);
        assertThat(captured.revisionNumber()).isEqualTo(1L);
        assertThat(captured.contentVersion()).isEqualTo(1L);
        assertThat(captured.chapterNumber()).isEqualTo(1);
        assertThat(captured.title()).isEqualTo("Chương 1: Mở đầu");
        assertThat(captured.slug().value()).isEqualTo("quyen-1-chuong-1");
        assertThat(captured.summary()).isEqualTo("Tóm tắt");
        assertThat(captured.content()).isEqualTo("Nội dung truyện");
        assertThat(captured.status()).isEqualTo(ChapterStatus.DRAFT);
        assertThat(captured.changeType()).isEqualTo(ChapterRevisionChangeType.CREATE_DRAFT);
        assertThat(captured.editSummary()).isEqualTo("Khởi tạo chương nháp");
        assertThat(captured.editedBy()).isEqualTo(actorId);
        assertThat(captured.createdAt()).isEqualTo(now);

        assertThat(result).isEqualTo(captured);
    }

    @Test
    void shouldThrowExceptionWhenParametersAreNull() {
        UUID actorId = UUID.randomUUID();
        Chapter chapter = Chapter.createDraft(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "Chương 1",
                new Slug("slug-1"),
                "",
                "",
                actorId,
                Instant.now()
        );

        assertThatThrownBy(() -> recorder.record(null, ChapterRevisionChangeType.CREATE_DRAFT, actorId, null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> recorder.record(chapter, null, actorId, null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> recorder.record(chapter, ChapterRevisionChangeType.CREATE_DRAFT, null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
