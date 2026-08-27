package com.universe.novel.domain.reference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.text.Normalizer;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ChapterWikiReference Domain Unit Tests")
class ChapterWikiReferenceTest {

    private final UUID referenceId = UUID.randomUUID();
    private final UUID chapterId = UUID.randomUUID();
    private final UUID wikiArticleId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-08-27T10:00:00Z");

    @Nested
    @DisplayName("Chapter-Wide Reference Creation")
    class ChapterWideCreationTests {

        @Test
        @DisplayName("Should create CHAPTER_WIDE reference with occurrenceIndex=0 and null contentVersion")
        void shouldCreateChapterWideReference() {
            ChapterWikiReference reference = ChapterWikiReference.createChapterWide(
                    referenceId,
                    chapterId,
                    "  Trần   Bình An  ",
                    wikiArticleId,
                    actorId,
                    now
            );

            assertThat(reference.getId()).isEqualTo(referenceId);
            assertThat(reference.getChapterId()).isEqualTo(chapterId);
            assertThat(reference.getReferenceScope()).isEqualTo(ChapterWikiReferenceScope.CHAPTER_WIDE);
            assertThat(reference.getOccurrenceIndex()).isEqualTo(0);
            assertThat(reference.getBoundContentVersion()).isNull();
            assertThat(reference.getContextSnippet()).isNull();
            assertThat(reference.getTerm()).isEqualTo("Trần Bình An");
            assertThat(reference.getNormalizedTerm()).isEqualTo("trần bình an");
            assertThat(reference.getWikiArticleId()).isEqualTo(wikiArticleId);
            assertThat(reference.getCreatedBy()).isEqualTo(actorId);
            assertThat(reference.getUpdatedBy()).isEqualTo(actorId);
            assertThat(reference.getCreatedAt()).isEqualTo(now);
            assertThat(reference.getUpdatedAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("Should normalize decomposed Unicode (NFD) to NFC")
        void shouldNormalizeUnicodeNFDToNFC() {
            String decomposed = Normalizer.normalize("Kiếm Lai", Normalizer.Form.NFD);
            ChapterWikiReference reference = ChapterWikiReference.createChapterWide(
                    referenceId,
                    chapterId,
                    decomposed,
                    wikiArticleId,
                    actorId,
                    now
            );

            assertThat(Normalizer.isNormalized(reference.getTerm(), Normalizer.Form.NFC)).isTrue();
            assertThat(reference.getTerm()).isEqualTo("Kiếm Lai");
            assertThat(reference.getNormalizedTerm()).isEqualTo("kiếm lai");
        }
    }

    @Nested
    @DisplayName("Occurrence-Specific Reference Creation")
    class OccurrenceSpecificCreationTests {

        @Test
        @DisplayName("Should create OCCURRENCE_SPECIFIC reference with occurrenceIndex>=1 and boundContentVersion>=1")
        void shouldCreateOccurrenceSpecificReference() {
            ChapterWikiReference reference = ChapterWikiReference.createOccurrenceSpecific(
                    referenceId,
                    chapterId,
                    "Nhất Khí Hóa Tam Thanh",
                    2,
                    "...lúc này thi triển Nhất Khí Hóa Tam Thanh về phía...",
                    3L,
                    wikiArticleId,
                    actorId,
                    now
            );

            assertThat(reference.getReferenceScope()).isEqualTo(ChapterWikiReferenceScope.OCCURRENCE_SPECIFIC);
            assertThat(reference.getOccurrenceIndex()).isEqualTo(2);
            assertThat(reference.getBoundContentVersion()).isEqualTo(3L);
            assertThat(reference.getContextSnippet()).isEqualTo("...lúc này thi triển Nhất Khí Hóa Tam Thanh về phía...");
            assertThat(reference.getTerm()).isEqualTo("Nhất Khí Hóa Tam Thanh");
            assertThat(reference.getNormalizedTerm()).isEqualTo("nhất khí hóa tam thanh");
        }

        @Test
        @DisplayName("Should reject OCCURRENCE_SPECIFIC reference when occurrenceIndex < 1")
        void shouldRejectOccurrenceIndexZeroOrNegative() {
            assertThatThrownBy(() -> ChapterWikiReference.createOccurrenceSpecific(
                    referenceId,
                    chapterId,
                    "Thuật ngữ",
                    0,
                    "Ngữ cảnh",
                    1L,
                    wikiArticleId,
                    actorId,
                    now
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Chỉ số xuất hiện");
        }

        @Test
        @DisplayName("Should reject OCCURRENCE_SPECIFIC reference when boundContentVersion < 1")
        void shouldRejectInvalidBoundContentVersion() {
            assertThatThrownBy(() -> ChapterWikiReference.createOccurrenceSpecific(
                    referenceId,
                    chapterId,
                    "Thuật ngữ",
                    1,
                    "Ngữ cảnh",
                    0L,
                    wikiArticleId,
                    actorId,
                    now
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Phiên bản nội dung ràng buộc");
        }
    }

    @Nested
    @DisplayName("Normalization and Invariant Validations")
    class ValidationTests {

        @Test
        @DisplayName("Should reject blank term")
        void shouldRejectBlankTerm() {
            assertThatThrownBy(() -> ChapterWikiReference.createChapterWide(
                    referenceId,
                    chapterId,
                    "   ",
                    wikiArticleId,
                    actorId,
                    now
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Thuật ngữ không được để trống.");
        }

        @Test
        @DisplayName("Should reject term exceeding 100 characters")
        void shouldRejectOverlongTerm() {
            String overlong = "a".repeat(101);
            assertThatThrownBy(() -> ChapterWikiReference.createChapterWide(
                    referenceId,
                    chapterId,
                    overlong,
                    wikiArticleId,
                    actorId,
                    now
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("không được vượt quá 100 ký tự");
        }

        @Test
        @DisplayName("Should update target Wiki article and audit timestamp")
        void shouldUpdateTargetArticle() {
            ChapterWikiReference reference = ChapterWikiReference.createChapterWide(
                    referenceId,
                    chapterId,
                    "Thuật ngữ",
                    wikiArticleId,
                    actorId,
                    now
            );

            UUID newArticleId = UUID.randomUUID();
            UUID newActorId = UUID.randomUUID();
            Instant later = now.plusSeconds(300);

            reference.updateTargetArticle(newArticleId, newActorId, later);

            assertThat(reference.getWikiArticleId()).isEqualTo(newArticleId);
            assertThat(reference.getUpdatedBy()).isEqualTo(newActorId);
            assertThat(reference.getUpdatedAt()).isEqualTo(later);
        }

        @Test
        @DisplayName("Should update occurrence context on OCCURRENCE_SPECIFIC reference")
        void shouldUpdateOccurrenceContext() {
            ChapterWikiReference reference = ChapterWikiReference.createOccurrenceSpecific(
                    referenceId,
                    chapterId,
                    "Thuật ngữ",
                    1,
                    "Cũ",
                    1L,
                    wikiArticleId,
                    actorId,
                    now
            );

            UUID newActorId = UUID.randomUUID();
            Instant later = now.plusSeconds(60);

            reference.updateOccurrenceContext(2, "Mới", 2L, newActorId, later);

            assertThat(reference.getOccurrenceIndex()).isEqualTo(2);
            assertThat(reference.getContextSnippet()).isEqualTo("Mới");
            assertThat(reference.getBoundContentVersion()).isEqualTo(2L);
            assertThat(reference.getUpdatedBy()).isEqualTo(newActorId);
            assertThat(reference.getUpdatedAt()).isEqualTo(later);
        }
    }
}
