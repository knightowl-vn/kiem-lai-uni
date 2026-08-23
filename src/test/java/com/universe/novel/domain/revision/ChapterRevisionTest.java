package com.universe.novel.domain.revision;

import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.Slug;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChapterRevisionTest {

    @Test
    void shouldCreateValidImmutableSnapshot() {
        UUID id = UUID.randomUUID();
        UUID chapterId = UUID.randomUUID();
        UUID volumeId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Instant now = Instant.now();

        ChapterRevision revision = new ChapterRevision(
                id,
                chapterId,
                volumeId,
                1L,
                1L,
                1,
                "Chương 1: Mở đầu",
                new Slug("quyen-1-chuong-1"),
                "Tóm tắt chương",
                "Nội dung chương văn bản...",
                ChapterStatus.DRAFT,
                ChapterRevisionChangeType.CREATE_DRAFT,
                "Khởi tạo chương nháp",
                actorId,
                now
        );

        assertThat(revision.id()).isEqualTo(id);
        assertThat(revision.chapterId()).isEqualTo(chapterId);
        assertThat(revision.volumeId()).isEqualTo(volumeId);
        assertThat(revision.revisionNumber()).isEqualTo(1L);
        assertThat(revision.contentVersion()).isEqualTo(1L);
        assertThat(revision.chapterNumber()).isEqualTo(1);
        assertThat(revision.title()).isEqualTo("Chương 1: Mở đầu");
        assertThat(revision.slug().value()).isEqualTo("quyen-1-chuong-1");
        assertThat(revision.summary()).isEqualTo("Tóm tắt chương");
        assertThat(revision.content()).isEqualTo("Nội dung chương văn bản...");
        assertThat(revision.status()).isEqualTo(ChapterStatus.DRAFT);
        assertThat(revision.changeType()).isEqualTo(ChapterRevisionChangeType.CREATE_DRAFT);
        assertThat(revision.editSummary()).isEqualTo("Khởi tạo chương nháp");
        assertThat(revision.editedBy()).isEqualTo(actorId);
        assertThat(revision.createdAt()).isEqualTo(now);
    }

    @Test
    void shouldCreateSnapshotFromChapterFactory() {
        UUID chapterId = UUID.randomUUID();
        UUID volumeId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Instant now = Instant.now();

        Chapter chapter = Chapter.createDraft(
                chapterId,
                volumeId,
                1,
                "Chương 1: Khởi đầu",
                new Slug("quyen-1-chuong-1"),
                "Tóm tắt",
                "Nội dung truyện",
                actorId,
                now
        );

        UUID revisionId = UUID.randomUUID();
        ChapterRevision snapshot = ChapterRevision.createSnapshot(
                revisionId,
                chapter,
                ChapterRevisionChangeType.CREATE_DRAFT,
                "Tạo bản nháp",
                actorId,
                now
        );

        assertThat(snapshot.id()).isEqualTo(revisionId);
        assertThat(snapshot.chapterId()).isEqualTo(chapterId);
        assertThat(snapshot.volumeId()).isEqualTo(volumeId);
        assertThat(snapshot.revisionNumber()).isEqualTo(1L);
        assertThat(snapshot.contentVersion()).isEqualTo(1L);
        assertThat(snapshot.chapterNumber()).isEqualTo(1);
        assertThat(snapshot.title()).isEqualTo("Chương 1: Khởi đầu");
        assertThat(snapshot.slug().value()).isEqualTo("quyen-1-chuong-1");
        assertThat(snapshot.summary()).isEqualTo("Tóm tắt");
        assertThat(snapshot.content()).isEqualTo("Nội dung truyện");
        assertThat(snapshot.status()).isEqualTo(ChapterStatus.DRAFT);
        assertThat(snapshot.changeType()).isEqualTo(ChapterRevisionChangeType.CREATE_DRAFT);
        assertThat(snapshot.editSummary()).isEqualTo("Tạo bản nháp");
        assertThat(snapshot.editedBy()).isEqualTo(actorId);
        assertThat(snapshot.createdAt()).isEqualTo(now);
    }

    @Test
    void shouldThrowExceptionWhenRequiredValuesAreNull() {
        UUID id = UUID.randomUUID();
        UUID chapterId = UUID.randomUUID();
        UUID volumeId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Instant now = Instant.now();

        assertThatThrownBy(() -> new ChapterRevision(
                null, chapterId, volumeId, 1L, 1L, 1, "Tiêu đề",
                new Slug("slug-1"), "", "", ChapterStatus.DRAFT,
                ChapterRevisionChangeType.CREATE_DRAFT, null, actorId, now
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new ChapterRevision(
                id, null, volumeId, 1L, 1L, 1, "Tiêu đề",
                new Slug("slug-1"), "", "", ChapterStatus.DRAFT,
                ChapterRevisionChangeType.CREATE_DRAFT, null, actorId, now
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new ChapterRevision(
                id, chapterId, null, 1L, 1L, 1, "Tiêu đề",
                new Slug("slug-1"), "", "", ChapterStatus.DRAFT,
                ChapterRevisionChangeType.CREATE_DRAFT, null, actorId, now
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new ChapterRevision(
                id, chapterId, volumeId, 1L, 1L, 1, "Tiêu đề",
                null, "", "", ChapterStatus.DRAFT,
                ChapterRevisionChangeType.CREATE_DRAFT, null, actorId, now
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new ChapterRevision(
                id, chapterId, volumeId, 1L, 1L, 1, "Tiêu đề",
                new Slug("slug-1"), "", "", null,
                ChapterRevisionChangeType.CREATE_DRAFT, null, actorId, now
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new ChapterRevision(
                id, chapterId, volumeId, 1L, 1L, 1, "Tiêu đề",
                new Slug("slug-1"), "", "", ChapterStatus.DRAFT,
                null, null, actorId, now
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new ChapterRevision(
                id, chapterId, volumeId, 1L, 1L, 1, "Tiêu đề",
                new Slug("slug-1"), "", "", ChapterStatus.DRAFT,
                ChapterRevisionChangeType.CREATE_DRAFT, null, null, now
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new ChapterRevision(
                id, chapterId, volumeId, 1L, 1L, 1, "Tiêu đề",
                new Slug("slug-1"), "", "", ChapterStatus.DRAFT,
                ChapterRevisionChangeType.CREATE_DRAFT, null, actorId, null
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldValidateVersionAndNumberRangeInvariants() {
        UUID id = UUID.randomUUID();
        UUID chapterId = UUID.randomUUID();
        UUID volumeId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Instant now = Instant.now();

        // revisionNumber < 1
        assertThatThrownBy(() -> new ChapterRevision(
                id, chapterId, volumeId, 0L, 1L, 1, "Tiêu đề",
                new Slug("slug-1"), "", "", ChapterStatus.DRAFT,
                ChapterRevisionChangeType.CREATE_DRAFT, null, actorId, now
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Revision number");

        // contentVersion < 1
        assertThatThrownBy(() -> new ChapterRevision(
                id, chapterId, volumeId, 1L, 0L, 1, "Tiêu đề",
                new Slug("slug-1"), "", "", ChapterStatus.DRAFT,
                ChapterRevisionChangeType.CREATE_DRAFT, null, actorId, now
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Content version");

        // chapterNumber < 1
        assertThatThrownBy(() -> new ChapterRevision(
                id, chapterId, volumeId, 1L, 1L, 0, "Tiêu đề",
                new Slug("slug-1"), "", "", ChapterStatus.DRAFT,
                ChapterRevisionChangeType.CREATE_DRAFT, null, actorId, now
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Số chương");
    }

    @Test
    void shouldValidateTextLengths() {
        UUID id = UUID.randomUUID();
        UUID chapterId = UUID.randomUUID();
        UUID volumeId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Instant now = Instant.now();

        // title too short (< 2)
        assertThatThrownBy(() -> new ChapterRevision(
                id, chapterId, volumeId, 1L, 1L, 1, "a",
                new Slug("slug-1"), "", "", ChapterStatus.DRAFT,
                ChapterRevisionChangeType.CREATE_DRAFT, null, actorId, now
        )).isInstanceOf(IllegalArgumentException.class);

        // title too long (> 250)
        assertThatThrownBy(() -> new ChapterRevision(
                id, chapterId, volumeId, 1L, 1L, 1, "a".repeat(251),
                new Slug("slug-1"), "", "", ChapterStatus.DRAFT,
                ChapterRevisionChangeType.CREATE_DRAFT, null, actorId, now
        )).isInstanceOf(IllegalArgumentException.class);

        // summary too long (> 1000)
        assertThatThrownBy(() -> new ChapterRevision(
                id, chapterId, volumeId, 1L, 1L, 1, "Tiêu đề hợp lệ",
                new Slug("slug-1"), "a".repeat(1001), "", ChapterStatus.DRAFT,
                ChapterRevisionChangeType.CREATE_DRAFT, null, actorId, now
        )).isInstanceOf(IllegalArgumentException.class);

        // content too long (> 500,000)
        assertThatThrownBy(() -> new ChapterRevision(
                id, chapterId, volumeId, 1L, 1L, 1, "Tiêu đề hợp lệ",
                new Slug("slug-1"), "", "a".repeat(500_001), ChapterStatus.DRAFT,
                ChapterRevisionChangeType.CREATE_DRAFT, null, actorId, now
        )).isInstanceOf(IllegalArgumentException.class);

        // editSummary too long (> 500)
        assertThatThrownBy(() -> new ChapterRevision(
                id, chapterId, volumeId, 1L, 1L, 1, "Tiêu đề hợp lệ",
                new Slug("slug-1"), "", "", ChapterStatus.DRAFT,
                ChapterRevisionChangeType.CREATE_DRAFT, "a".repeat(501), actorId, now
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
