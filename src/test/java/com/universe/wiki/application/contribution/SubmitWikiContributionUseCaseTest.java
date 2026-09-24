package com.universe.wiki.application.contribution;

import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.exceptions.PublishedWikiArticleNotFoundException;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.application.ports.WikiContributionRepositoryPort;
import com.universe.wiki.application.ports.WikiContributionSourceRepositoryPort;
import com.universe.wiki.domain.article.ArticleStatus;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.domain.article.Slug;
import com.universe.wiki.domain.article.WikiArticle;
import com.universe.wiki.domain.contribution.WikiContribution;
import com.universe.wiki.domain.contribution.WikiContributionContextType;
import com.universe.wiki.domain.contribution.WikiContributionSource;
import com.universe.wiki.domain.contribution.WikiContributionSourceType;
import com.universe.wiki.domain.contribution.WikiContributionStatus;
import com.universe.wiki.domain.contribution.WikiContributionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubmitWikiContributionUseCase Unit Tests")
class SubmitWikiContributionUseCaseTest {

    private static final UUID ARTICLE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID AUTHOR_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant FIXED_NOW = Instant.parse("2026-09-24T12:00:00Z");

    @Mock
    private WikiArticleRepositoryPort articleRepository;

    @Mock
    private WikiContributionRepositoryPort contributionRepository;

    @Mock
    private WikiContributionSourceRepositoryPort sourceRepository;

    @Mock
    private ClockPort clockPort;

    private SubmitWikiContributionUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new SubmitWikiContributionUseCase(
                articleRepository,
                contributionRepository,
                sourceRepository,
                clockPort
        );
    }

    private WikiArticle createMockArticle(ArticleStatus status, long contentVersion) {
        Instant past = FIXED_NOW.minusSeconds(3600);
        return WikiArticle.rehydrate(
                ARTICLE_ID,
                "Trần Bình An",
                new Slug("tran-binh-an"),
                ArticleType.CHARACTER,
                "Tóm tắt nhân vật Trần Bình An",
                "Nội dung bài viết về kiếm khí trường thành.",
                null, 50, 50,
                status,
                AUTHOR_ID, AUTHOR_ID,
                status == ArticleStatus.PUBLISHED ? AUTHOR_ID : null,
                status == ArticleStatus.ARCHIVED ? AUTHOR_ID : null,
                past, past,
                status == ArticleStatus.PUBLISHED ? past : null,
                status == ArticleStatus.ARCHIVED ? past : null,
                contentVersion,
                contentVersion
        );
    }

    @Nested
    @DisplayName("1. Constructor validation")
    class ConstructorValidationTests {

        @Test
        @DisplayName("Ném NullPointerException khi bất kỳ dependency nào bị null")
        void shouldThrowWhenAnyDependencyIsNull() {
            assertThatThrownBy(() -> new SubmitWikiContributionUseCase(null, contributionRepository, sourceRepository, clockPort))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("WikiArticleRepositoryPort");

            assertThatThrownBy(() -> new SubmitWikiContributionUseCase(articleRepository, null, sourceRepository, clockPort))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("WikiContributionRepositoryPort");

            assertThatThrownBy(() -> new SubmitWikiContributionUseCase(articleRepository, contributionRepository, null, clockPort))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("WikiContributionSourceRepositoryPort");

            assertThatThrownBy(() -> new SubmitWikiContributionUseCase(articleRepository, contributionRepository, sourceRepository, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("ClockPort");
        }
    }

    @Nested
    @DisplayName("2. Article verification & Content version checks")
    class ArticleVerificationTests {

        @Test
        @DisplayName("Ném PublishedWikiArticleNotFoundException khi bài viết không tồn tại")
        void shouldThrowWhenArticleNotFound() {
            when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.empty());

            SubmitWikiContributionCommand command = new SubmitWikiContributionCommand(
                    ARTICLE_ID,
                    USER_ID,
                    1L,
                    "GENERAL",
                    "INCORRECT_INFORMATION",
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    null, null, null, null,
                    Collections.emptyList()
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(PublishedWikiArticleNotFoundException.class);

            verifyNoInteractions(contributionRepository, sourceRepository);
        }

        @Test
        @DisplayName("Ném PublishedWikiArticleNotFoundException khi bài viết ở trạng thái DRAFT")
        void shouldThrowWhenArticleIsDraft() {
            WikiArticle draftArticle = createMockArticle(ArticleStatus.DRAFT, 1L);
            when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.of(draftArticle));

            SubmitWikiContributionCommand command = new SubmitWikiContributionCommand(
                    ARTICLE_ID,
                    USER_ID,
                    1L,
                    "GENERAL",
                    "INCORRECT_INFORMATION",
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    null, null, null, null,
                    Collections.emptyList()
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(PublishedWikiArticleNotFoundException.class);

            verifyNoInteractions(contributionRepository, sourceRepository);
        }

        @Test
        @DisplayName("Ném PublishedWikiArticleNotFoundException khi bài viết ở trạng thái ARCHIVED")
        void shouldThrowWhenArticleIsArchived() {
            WikiArticle archivedArticle = createMockArticle(ArticleStatus.ARCHIVED, 1L);
            when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.of(archivedArticle));

            SubmitWikiContributionCommand command = new SubmitWikiContributionCommand(
                    ARTICLE_ID,
                    USER_ID,
                    1L,
                    "GENERAL",
                    "INCORRECT_INFORMATION",
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    null, null, null, null,
                    Collections.emptyList()
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(PublishedWikiArticleNotFoundException.class);

            verifyNoInteractions(contributionRepository, sourceRepository);
        }

        @Test
        @DisplayName("Ném IllegalArgumentException khi readerVersion là null (không ném NullPointerException)")
        void shouldThrowWhenReaderVersionIsNull() {
            SubmitWikiContributionCommand command = new SubmitWikiContributionCommand(
                    ARTICLE_ID,
                    USER_ID,
                    null,
                    "GENERAL",
                    "INCORRECT_INFORMATION",
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    null, null, null, null,
                    Collections.emptyList()
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Phiên bản nội dung bài viết");

            verifyNoInteractions(contributionRepository, sourceRepository);
        }

        @Test
        @DisplayName("Ném IllegalArgumentException khi contextType là chuỗi không hợp lệ (không fallback silently)")
        void shouldThrowWhenContextTypeIsMalformed() {
            SubmitWikiContributionCommand command = new SubmitWikiContributionCommand(
                    ARTICLE_ID,
                    USER_ID,
                    1L,
                    "SOMETHING_INVALID",
                    "WORDING",
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    null, null, null, null,
                    Collections.emptyList()
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Loại ngữ cảnh đóng góp không hợp lệ: SOMETHING_INVALID");

            verifyNoInteractions(contributionRepository, sourceRepository);
        }

        @Test
        @DisplayName("Ném IllegalArgumentException khi contributionType là chuỗi không hợp lệ (không fallback silently)")
        void shouldThrowWhenContributionTypeIsMalformed() {
            SubmitWikiContributionCommand command = new SubmitWikiContributionCommand(
                    ARTICLE_ID,
                    USER_ID,
                    1L,
                    "GENERAL",
                    "NOT_A_TYPE",
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    null, null, null, null,
                    Collections.emptyList()
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Loại đóng góp không hợp lệ: NOT_A_TYPE");

            verifyNoInteractions(contributionRepository, sourceRepository);
        }

        @Test
        @DisplayName("Ném IllegalArgumentException khi readerVersion < 1")
        void shouldThrowWhenReaderVersionIsLessThanOne() {
            SubmitWikiContributionCommand command = new SubmitWikiContributionCommand(
                    ARTICLE_ID,
                    USER_ID,
                    0L,
                    "GENERAL",
                    "INCORRECT_INFORMATION",
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    null, null, null, null,
                    Collections.emptyList()
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Phiên bản nội dung bài viết");
        }

        @Test
        @DisplayName("Ném IllegalArgumentException khi readerVersion > currentVersion của bài viết")
        void shouldThrowWhenReaderVersionIsGreaterThanCurrentVersion() {
            WikiArticle publishedArticle = createMockArticle(ArticleStatus.PUBLISHED, 2L);
            when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.of(publishedArticle));

            SubmitWikiContributionCommand command = new SubmitWikiContributionCommand(
                    ARTICLE_ID,
                    USER_ID,
                    3L, // article hiện tại là 2L
                    "GENERAL",
                    "INCORRECT_INFORMATION",
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    null, null, null, null,
                    Collections.emptyList()
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("không được lớn hơn phiên bản hiện tại");
        }

        @Test
        @DisplayName("Chấp nhận khi độc giả thấy version N trong khi bài viết đã lên N+1 (stale version accepted)")
        void shouldAcceptStaleReaderVersion() {
            WikiArticle publishedArticle = createMockArticle(ArticleStatus.PUBLISHED, 2L);
            when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.of(publishedArticle));
            when(clockPort.now()).thenReturn(FIXED_NOW);
            when(contributionRepository.findRecentCandidates(any(), any(), any(), eq(10))).thenReturn(Collections.emptyList());
            when(contributionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            SubmitWikiContributionCommand command = new SubmitWikiContributionCommand(
                    ARTICLE_ID,
                    USER_ID,
                    1L, // reader saw version 1 while current is 2
                    "GENERAL",
                    "WORDING",
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    null, null, null, null,
                    Collections.emptyList()
            );

            SubmitWikiContributionResult result = useCase.execute(command);
            assertThat(result.alreadySubmitted()).isFalse();

            ArgumentCaptor<WikiContribution> captor = ArgumentCaptor.forClass(WikiContribution.class);
            verify(contributionRepository).save(captor.capture());
            assertThat(captor.getValue().getArticleContentVersion()).isEqualTo(1L);
            assertThat(captor.getValue().getArticleTitleSnapshot()).isEqualTo(publishedArticle.getTitle());
            assertThat(captor.getValue().getArticleSlugSnapshot()).isEqualTo("tran-binh-an");
            assertThat(captor.getValue().getArticleTypeSnapshot()).isEqualTo("CHARACTER");
        }
    }

    @Nested
    @DisplayName("3. Source URL validation & canonicalization")
    class SourceValidationTests {

        @Test
        @DisplayName("Ném IllegalArgumentException khi số lượng nguồn > 5")
        void shouldThrowWhenSourcesCountExceedsFive() {
            WikiArticle publishedArticle = createMockArticle(ArticleStatus.PUBLISHED, 1L);
            when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.of(publishedArticle));

            List<String> tooManySources = List.of(
                    "https://src1.example.com",
                    "https://src2.example.com",
                    "https://src3.example.com",
                    "https://src4.example.com",
                    "https://src5.example.com",
                    "https://src6.example.com"
            );

            SubmitWikiContributionCommand command = new SubmitWikiContributionCommand(
                    ARTICLE_ID,
                    USER_ID,
                    1L,
                    "GENERAL",
                    "SOURCE_REFERENCE",
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    null, null, null, null,
                    tooManySources
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tối đa 5 nguồn");
        }

        @Test
        @DisplayName("Ném IllegalArgumentException khi có URL trùng lặp nguyên bản trong cùng một submission")
        void shouldThrowWhenDuplicateRawSourcesProvided() {
            WikiArticle publishedArticle = createMockArticle(ArticleStatus.PUBLISHED, 1L);
            when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.of(publishedArticle));
            when(clockPort.now()).thenReturn(FIXED_NOW);

            List<String> duplicateSources = List.of(
                    "https://example.com/source",
                    "https://example.com/source"
            );

            SubmitWikiContributionCommand command = new SubmitWikiContributionCommand(
                    ARTICLE_ID,
                    USER_ID,
                    1L,
                    "GENERAL",
                    "SOURCE_REFERENCE",
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    null, null, null, null,
                    duplicateSources
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("trùng lặp");
        }

        @Test
        @DisplayName("Ném IllegalArgumentException khi có URL tương đương sau chuẩn hóa (duplicate canonical URLs)")
        void shouldThrowWhenCanonicalEquivalentSourcesProvided() {
            WikiArticle publishedArticle = createMockArticle(ArticleStatus.PUBLISHED, 1L);
            when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.of(publishedArticle));
            when(clockPort.now()).thenReturn(FIXED_NOW);

            // Hai URL nội bộ khác cú pháp nhưng cùng chuẩn hóa về "/wiki/articles/tran-binh-an"
            List<String> equivalentSources = List.of(
                    "/wiki/articles/tran-binh-an",
                    "/wiki/articles/../articles/tran-binh-an"
            );

            SubmitWikiContributionCommand command = new SubmitWikiContributionCommand(
                    ARTICLE_ID,
                    USER_ID,
                    1L,
                    "GENERAL",
                    "SOURCE_REFERENCE",
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    null, null, null, null,
                    equivalentSources
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("trùng lặp");
        }

        @Test
        @DisplayName("Ném IllegalArgumentException và không bao giờ gọi contributionRepository.save khi nguồn tham khảo không hợp lệ")
        void shouldNeverCallContributionSaveWhenSourceValidationFails() {
            WikiArticle publishedArticle = createMockArticle(ArticleStatus.PUBLISHED, 1L);
            when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.of(publishedArticle));
            when(clockPort.now()).thenReturn(FIXED_NOW);

            SubmitWikiContributionCommand command = new SubmitWikiContributionCommand(
                    ARTICLE_ID,
                    USER_ID,
                    1L,
                    "GENERAL",
                    "SOURCE_REFERENCE",
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    null, null, null, null,
                    List.of("ftp://invalid-protocol.example.com")
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(contributionRepository, never()).save(any());
            verify(sourceRepository, never()).saveAll(any());
        }
    }

    @Nested
    @DisplayName("4. 60-second duplicate submission guard")
    class DuplicateGuardTests {

        @Test
        @DisplayName("Phát hiện đóng góp trùng lặp trong 60s -> trả về alreadySubmitted = true và không lưu mới")
        void shouldDetectDuplicateSubmissionWithinSixtySeconds() {
            WikiArticle publishedArticle = createMockArticle(ArticleStatus.PUBLISHED, 1L);
            when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.of(publishedArticle));
            when(clockPort.now()).thenReturn(FIXED_NOW);

            UUID existingContributionId = UUID.randomUUID();
            WikiContribution existingCandidate = WikiContribution.createGeneral(
                    existingContributionId,
                    ARTICLE_ID,
                    "CHARACTER",
                    "Trần Bình An",
                    "tran-binh-an",
                    1L,
                    USER_ID,
                    WikiContributionType.WORDING,
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    FIXED_NOW.minusSeconds(10)
            );

            when(contributionRepository.findRecentCandidates(eq(USER_ID), eq(ARTICLE_ID), any(), eq(10)))
                    .thenReturn(List.of(existingCandidate));

            WikiContributionSource existingSource1 = WikiContributionSource.create(
                    UUID.randomUUID(), existingContributionId, 0, "https://src1.example.com", FIXED_NOW.minusSeconds(10)
            );
            WikiContributionSource existingSource2 = WikiContributionSource.create(
                    UUID.randomUUID(), existingContributionId, 1, "https://src2.example.com", FIXED_NOW.minusSeconds(10)
            );
            when(sourceRepository.findByContributionId(existingContributionId))
                    .thenReturn(List.of(existingSource1, existingSource2));

            // Submission mới đảo thứ tự sources (order-independent matching)
            SubmitWikiContributionCommand command = new SubmitWikiContributionCommand(
                    ARTICLE_ID,
                    USER_ID,
                    1L,
                    "GENERAL",
                    "WORDING",
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    null, null, null, null,
                    List.of("https://src2.example.com", "https://src1.example.com")
            );

            SubmitWikiContributionResult result = useCase.execute(command);

            assertThat(result.alreadySubmitted()).isTrue();
            assertThat(result.contributionId()).isEqualTo(existingContributionId);
            assertThat(result.status()).isEqualTo("NEW");
            assertThat(result.message()).contains("Đóng góp tương tự đã được gửi trước đó");

            verify(contributionRepository, never()).save(any());
            verify(sourceRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("Không coi là trùng lặp nếu nội dung message khác nhau")
        void shouldNotTreatAsDuplicateWhenMessageDiffers() {
            WikiArticle publishedArticle = createMockArticle(ArticleStatus.PUBLISHED, 1L);
            when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.of(publishedArticle));
            when(clockPort.now()).thenReturn(FIXED_NOW);

            UUID existingContributionId = UUID.randomUUID();
            WikiContribution existingCandidate = WikiContribution.createGeneral(
                    existingContributionId,
                    ARTICLE_ID,
                    "CHARACTER",
                    "Trần Bình An",
                    "tran-binh-an",
                    1L,
                    USER_ID,
                    WikiContributionType.WORDING,
                    "Nội dung đóng góp cũ có độ dài trên hai mươi ký tự.",
                    FIXED_NOW.minusSeconds(10)
            );

            when(contributionRepository.findRecentCandidates(eq(USER_ID), eq(ARTICLE_ID), any(), eq(10)))
                    .thenReturn(List.of(existingCandidate));
            when(contributionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            SubmitWikiContributionCommand command = new SubmitWikiContributionCommand(
                    ARTICLE_ID,
                    USER_ID,
                    1L,
                    "GENERAL",
                    "WORDING",
                    "Nội dung đóng góp mới hoàn toàn khác nội dung cũ trên hai mươi ký tự.",
                    null, null, null, null,
                    Collections.emptyList()
            );

            SubmitWikiContributionResult result = useCase.execute(command);

            assertThat(result.alreadySubmitted()).isFalse();
            verify(contributionRepository).save(any());
        }

        @Test
        @DisplayName("Không coi là trùng lặp nếu danh sách nguồn khác nhau")
        void shouldNotTreatAsDuplicateWhenSourcesDiffer() {
            WikiArticle publishedArticle = createMockArticle(ArticleStatus.PUBLISHED, 1L);
            when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.of(publishedArticle));
            when(clockPort.now()).thenReturn(FIXED_NOW);

            UUID existingContributionId = UUID.randomUUID();
            WikiContribution existingCandidate = WikiContribution.createGeneral(
                    existingContributionId,
                    ARTICLE_ID,
                    "CHARACTER",
                    "Trần Bình An",
                    "tran-binh-an",
                    1L,
                    USER_ID,
                    WikiContributionType.WORDING,
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    FIXED_NOW.minusSeconds(10)
            );

            when(contributionRepository.findRecentCandidates(eq(USER_ID), eq(ARTICLE_ID), any(), eq(10)))
                    .thenReturn(List.of(existingCandidate));

            WikiContributionSource existingSource = WikiContributionSource.create(
                    UUID.randomUUID(), existingContributionId, 0, "https://old.example.com", FIXED_NOW.minusSeconds(10)
            );
            when(sourceRepository.findByContributionId(existingContributionId))
                    .thenReturn(List.of(existingSource));
            when(contributionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            SubmitWikiContributionCommand command = new SubmitWikiContributionCommand(
                    ARTICLE_ID,
                    USER_ID,
                    1L,
                    "GENERAL",
                    "WORDING",
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    null, null, null, null,
                    List.of("https://new.example.com")
            );

            SubmitWikiContributionResult result = useCase.execute(command);

            assertThat(result.alreadySubmitted()).isFalse();
            verify(contributionRepository).save(any());
            verify(sourceRepository).saveAll(any());
        }

        @Test
        @DisplayName("Phát hiện đóng góp trùng lặp tại đúng biên 60 giây (created exactly 60s ago -> RECENT)")
        void shouldDetectDuplicateAtExactSixtySecondsBoundary() {
            WikiArticle publishedArticle = createMockArticle(ArticleStatus.PUBLISHED, 1L);
            when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.of(publishedArticle));
            when(clockPort.now()).thenReturn(FIXED_NOW);

            UUID existingContributionId = UUID.randomUUID();
            Instant exactlySixtySecAgo = FIXED_NOW.minusSeconds(60);
            WikiContribution existingCandidate = WikiContribution.createGeneral(
                    existingContributionId,
                    ARTICLE_ID,
                    "CHARACTER",
                    "Trần Bình An",
                    "tran-binh-an",
                    1L,
                    USER_ID,
                    WikiContributionType.WORDING,
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    exactlySixtySecAgo
            );

            when(contributionRepository.findRecentCandidates(eq(USER_ID), eq(ARTICLE_ID), eq(exactlySixtySecAgo), eq(10)))
                    .thenReturn(List.of(existingCandidate));
            when(sourceRepository.findByContributionId(existingContributionId))
                    .thenReturn(Collections.emptyList());

            SubmitWikiContributionCommand command = new SubmitWikiContributionCommand(
                    ARTICLE_ID,
                    USER_ID,
                    1L,
                    "GENERAL",
                    "WORDING",
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    null, null, null, null,
                    Collections.emptyList()
            );

            SubmitWikiContributionResult result = useCase.execute(command);

            assertThat(result.alreadySubmitted()).isTrue();
            assertThat(result.contributionId()).isEqualTo(existingContributionId);
            verify(contributionRepository, never()).save(any());
        }

        @Test
        @DisplayName("Cho phép tạo mới khi đóng góp cũ vượt quá 60 giây (strictly older than 60s -> NOT RECENT)")
        void shouldAllowNewSubmissionWhenCandidateIsStrictlyOlderThanSixtySeconds() {
            WikiArticle publishedArticle = createMockArticle(ArticleStatus.PUBLISHED, 1L);
            when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.of(publishedArticle));
            when(clockPort.now()).thenReturn(FIXED_NOW);

            Instant cutoff = FIXED_NOW.minusSeconds(60);
            when(contributionRepository.findRecentCandidates(eq(USER_ID), eq(ARTICLE_ID), eq(cutoff), eq(10)))
                    .thenReturn(Collections.emptyList());
            when(contributionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            SubmitWikiContributionCommand command = new SubmitWikiContributionCommand(
                    ARTICLE_ID,
                    USER_ID,
                    1L,
                    "GENERAL",
                    "WORDING",
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    null, null, null, null,
                    Collections.emptyList()
            );

            SubmitWikiContributionResult result = useCase.execute(command);

            assertThat(result.alreadySubmitted()).isFalse();
            verify(contributionRepository).save(any());
        }

        @Test
        @DisplayName("Không coi là trùng lặp khi danh sách nguồn khác nhau (ví dụ: [X] vs [X, Y])")
        void shouldNotTreatAsDuplicateWhenSourcesAreSubsetOrSuperset() {
            WikiArticle publishedArticle = createMockArticle(ArticleStatus.PUBLISHED, 1L);
            when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.of(publishedArticle));
            when(clockPort.now()).thenReturn(FIXED_NOW);

            UUID existingContributionId = UUID.randomUUID();
            WikiContribution existingCandidate = WikiContribution.createGeneral(
                    existingContributionId,
                    ARTICLE_ID,
                    "CHARACTER",
                    "Trần Bình An",
                    "tran-binh-an",
                    1L,
                    USER_ID,
                    WikiContributionType.WORDING,
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    FIXED_NOW.minusSeconds(10)
            );

            when(contributionRepository.findRecentCandidates(eq(USER_ID), eq(ARTICLE_ID), any(), eq(10)))
                    .thenReturn(List.of(existingCandidate));

            // Candidate chỉ có nguồn X
            WikiContributionSource sourceX = WikiContributionSource.create(
                    UUID.randomUUID(), existingContributionId, 0, "https://src-x.example.com", FIXED_NOW.minusSeconds(10)
            );
            when(sourceRepository.findByContributionId(existingContributionId))
                    .thenReturn(List.of(sourceX));
            when(contributionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            // Incoming có cả X và Y
            SubmitWikiContributionCommand commandWithXY = new SubmitWikiContributionCommand(
                    ARTICLE_ID,
                    USER_ID,
                    1L,
                    "GENERAL",
                    "WORDING",
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    null, null, null, null,
                    List.of("https://src-x.example.com", "https://src-y.example.com")
            );

            SubmitWikiContributionResult result = useCase.execute(commandWithXY);

            assertThat(result.alreadySubmitted()).isFalse();
            verify(contributionRepository).save(any());
            verify(sourceRepository).saveAll(any());
        }

        @Test
        @DisplayName("Không coi là trùng lặp khi nội dung có khoảng trắng nội bộ hoặc xuống dòng khác biệt")
        void shouldNotTreatAsDuplicateWhenInternalWhitespaceOrNewlinesDiffer() {
            WikiArticle publishedArticle = createMockArticle(ArticleStatus.PUBLISHED, 1L);
            when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.of(publishedArticle));
            when(clockPort.now()).thenReturn(FIXED_NOW);

            UUID existingContributionId = UUID.randomUUID();
            WikiContribution existingCandidate = WikiContribution.createGeneral(
                    existingContributionId,
                    ARTICLE_ID,
                    "CHARACTER",
                    "Trần Bình An",
                    "tran-binh-an",
                    1L,
                    USER_ID,
                    WikiContributionType.WORDING,
                    "Thông tin dòng một.\nThông tin dòng hai.",
                    FIXED_NOW.minusSeconds(10)
            );

            when(contributionRepository.findRecentCandidates(eq(USER_ID), eq(ARTICLE_ID), any(), eq(10)))
                    .thenReturn(List.of(existingCandidate));
            when(contributionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            SubmitWikiContributionCommand commandDiffWhitespace = new SubmitWikiContributionCommand(
                    ARTICLE_ID,
                    USER_ID,
                    1L,
                    "GENERAL",
                    "WORDING",
                    "Thông tin dòng một. Thông tin dòng hai.",
                    null, null, null, null,
                    Collections.emptyList()
            );

            SubmitWikiContributionResult result = useCase.execute(commandDiffWhitespace);

            assertThat(result.alreadySubmitted()).isFalse();
            verify(contributionRepository).save(any());
        }

        @Test
        @DisplayName("Coi là trùng lặp khi chỉ khác khoảng trắng đầu/cuối được chuẩn hóa (trimmed outer whitespace)")
        void shouldTreatAsDuplicateWhenOnlyOuterWhitespaceDiffers() {
            WikiArticle publishedArticle = createMockArticle(ArticleStatus.PUBLISHED, 1L);
            when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.of(publishedArticle));
            when(clockPort.now()).thenReturn(FIXED_NOW);

            UUID existingContributionId = UUID.randomUUID();
            WikiContribution existingCandidate = WikiContribution.createGeneral(
                    existingContributionId,
                    ARTICLE_ID,
                    "CHARACTER",
                    "Trần Bình An",
                    "tran-binh-an",
                    1L,
                    USER_ID,
                    WikiContributionType.WORDING,
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    FIXED_NOW.minusSeconds(10)
            );

            when(contributionRepository.findRecentCandidates(eq(USER_ID), eq(ARTICLE_ID), any(), eq(10)))
                    .thenReturn(List.of(existingCandidate));
            when(sourceRepository.findByContributionId(existingContributionId))
                    .thenReturn(Collections.emptyList());

            SubmitWikiContributionCommand commandPadded = new SubmitWikiContributionCommand(
                    ARTICLE_ID,
                    USER_ID,
                    1L,
                    "GENERAL",
                    "WORDING",
                    "   Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.   ",
                    null, null, null, null,
                    Collections.emptyList()
            );

            SubmitWikiContributionResult result = useCase.execute(commandPadded);

            assertThat(result.alreadySubmitted()).isTrue();
            assertThat(result.contributionId()).isEqualTo(existingContributionId);
            verify(contributionRepository, never()).save(any());
        }

        @Test
        @DisplayName("Không coi là trùng lặp nếu loại ngữ cảnh (contextType) khác nhau")
        void shouldNotTreatAsDuplicateWhenContextTypeDiffers() {
            WikiArticle publishedArticle = createMockArticle(ArticleStatus.PUBLISHED, 1L);
            when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.of(publishedArticle));
            when(clockPort.now()).thenReturn(FIXED_NOW);

            UUID existingContributionId = UUID.randomUUID();
            WikiContribution existingCandidate = WikiContribution.createTextSelection(
                    existingContributionId,
                    ARTICLE_ID,
                    "CHARACTER",
                    "Trần Bình An",
                    "tran-binh-an",
                    1L,
                    USER_ID,
                    WikiContributionType.WORDING,
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    "Đoạn trích dẫn",
                    null, null, null,
                    FIXED_NOW.minusSeconds(10)
            );

            when(contributionRepository.findRecentCandidates(eq(USER_ID), eq(ARTICLE_ID), any(), eq(10)))
                    .thenReturn(List.of(existingCandidate));
            when(contributionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            SubmitWikiContributionCommand generalCommand = new SubmitWikiContributionCommand(
                    ARTICLE_ID,
                    USER_ID,
                    1L,
                    "GENERAL",
                    "WORDING",
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    null, null, null, null,
                    Collections.emptyList()
            );

            SubmitWikiContributionResult result = useCase.execute(generalCommand);

            assertThat(result.alreadySubmitted()).isFalse();
            verify(contributionRepository).save(any());
        }

        @Test
        @DisplayName("Không coi là trùng lặp nếu loại đóng góp (contributionType) khác nhau")
        void shouldNotTreatAsDuplicateWhenContributionTypeDiffers() {
            WikiArticle publishedArticle = createMockArticle(ArticleStatus.PUBLISHED, 1L);
            when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.of(publishedArticle));
            when(clockPort.now()).thenReturn(FIXED_NOW);

            UUID existingContributionId = UUID.randomUUID();
            WikiContribution existingCandidate = WikiContribution.createGeneral(
                    existingContributionId,
                    ARTICLE_ID,
                    "CHARACTER",
                    "Trần Bình An",
                    "tran-binh-an",
                    1L,
                    USER_ID,
                    WikiContributionType.WORDING,
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    FIXED_NOW.minusSeconds(10)
            );

            when(contributionRepository.findRecentCandidates(eq(USER_ID), eq(ARTICLE_ID), any(), eq(10)))
                    .thenReturn(List.of(existingCandidate));
            when(contributionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            SubmitWikiContributionCommand diffTypeCommand = new SubmitWikiContributionCommand(
                    ARTICLE_ID,
                    USER_ID,
                    1L,
                    "GENERAL",
                    "INCORRECT_INFORMATION",
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    null, null, null, null,
                    Collections.emptyList()
            );

            SubmitWikiContributionResult result = useCase.execute(diffTypeCommand);

            assertThat(result.alreadySubmitted()).isFalse();
            verify(contributionRepository).save(any());
        }

        @Test
        @DisplayName("Không coi là trùng lặp nếu văn bản chọn (selectedText) khác nhau")
        void shouldNotTreatAsDuplicateWhenSelectedTextDiffers() {
            WikiArticle publishedArticle = createMockArticle(ArticleStatus.PUBLISHED, 1L);
            when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.of(publishedArticle));
            when(clockPort.now()).thenReturn(FIXED_NOW);

            UUID existingContributionId = UUID.randomUUID();
            WikiContribution existingCandidate = WikiContribution.createTextSelection(
                    existingContributionId,
                    ARTICLE_ID,
                    "CHARACTER",
                    "Trần Bình An",
                    "tran-binh-an",
                    1L,
                    USER_ID,
                    WikiContributionType.WORDING,
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    "Đoạn trích dẫn ban đầu",
                    null, null, null,
                    FIXED_NOW.minusSeconds(10)
            );

            when(contributionRepository.findRecentCandidates(eq(USER_ID), eq(ARTICLE_ID), any(), eq(10)))
                    .thenReturn(List.of(existingCandidate));
            when(contributionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            SubmitWikiContributionCommand diffSelectionCommand = new SubmitWikiContributionCommand(
                    ARTICLE_ID,
                    USER_ID,
                    1L,
                    "TEXT_SELECTION",
                    "WORDING",
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    "Đoạn trích dẫn hoàn toàn khác",
                    null, null, null,
                    Collections.emptyList()
            );

            SubmitWikiContributionResult result = useCase.execute(diffSelectionCommand);

            assertThat(result.alreadySubmitted()).isFalse();
            verify(contributionRepository).save(any());
        }
    }

    @Nested
    @DisplayName("5. Context TEXT_SELECTION vs GENERAL")
    class ContextTypesTests {

        @Test
        @DisplayName("Tạo mới thành công đóng góp TEXT_SELECTION với đầy đủ neo và nguồn")
        void shouldCreateTextSelectionContributionSuccessfully() {
            WikiArticle publishedArticle = createMockArticle(ArticleStatus.PUBLISHED, 1L);
            when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.of(publishedArticle));
            when(clockPort.now()).thenReturn(FIXED_NOW);
            when(contributionRepository.findRecentCandidates(any(), any(), any(), eq(10))).thenReturn(Collections.emptyList());
            when(contributionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            SubmitWikiContributionCommand command = new SubmitWikiContributionCommand(
                    ARTICLE_ID,
                    USER_ID,
                    1L,
                    "TEXT_SELECTION",
                    "INCORRECT_INFORMATION",
                    "Chi tiết này không chính xác theo nguyên tác chương 15.",
                    "Kiếm khí trường thành sừng sững",
                    "Tiền tố đoạn văn",
                    "Hậu tố đoạn văn",
                    "tieu-de-chuong-15",
                    List.of("/wiki/articles/kiem-khi-truong-thanh")
            );

            SubmitWikiContributionResult result = useCase.execute(command);

            assertThat(result.alreadySubmitted()).isFalse();
            assertThat(result.status()).isEqualTo("NEW");

            ArgumentCaptor<WikiContribution> contribCaptor = ArgumentCaptor.forClass(WikiContribution.class);
            verify(contributionRepository).save(contribCaptor.capture());
            WikiContribution saved = contribCaptor.getValue();
            assertThat(saved.getContextType()).isEqualTo(WikiContributionContextType.TEXT_SELECTION);
            assertThat(saved.getSelectedText()).isEqualTo("Kiếm khí trường thành sừng sững");
            assertThat(saved.getSelectedPrefix()).isEqualTo("Tiền tố đoạn văn");
            assertThat(saved.getSelectedSuffix()).isEqualTo("Hậu tố đoạn văn");
            assertThat(saved.getSelectedHeadingAnchor()).isEqualTo("tieu-de-chuong-15");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<WikiContributionSource>> sourcesCaptor = ArgumentCaptor.forClass(List.class);
            verify(sourceRepository).saveAll(sourcesCaptor.capture());
            List<WikiContributionSource> savedSources = sourcesCaptor.getValue();
            assertThat(savedSources).hasSize(1);
            assertThat(savedSources.get(0).getSourceType()).isEqualTo(WikiContributionSourceType.INTERNAL);
            assertThat(savedSources.get(0).getUrl()).isEqualTo("/wiki/articles/kiem-khi-truong-thanh");
        }

        @Test
        @DisplayName("Ném IllegalArgumentException khi đóng góp GENERAL có kèm selectedText")
        void shouldThrowWhenGeneralContributionContainsSelectedText() {
            WikiArticle publishedArticle = createMockArticle(ArticleStatus.PUBLISHED, 1L);
            when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.of(publishedArticle));
            when(clockPort.now()).thenReturn(FIXED_NOW);

            SubmitWikiContributionCommand command = new SubmitWikiContributionCommand(
                    ARTICLE_ID,
                    USER_ID,
                    1L,
                    "GENERAL",
                    "OTHER",
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    "Đoạn trích không được phép xuất hiện ở GENERAL",
                    null, null, null,
                    Collections.emptyList()
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tổng quan không được chứa thông tin trích dẫn");
        }

        @Test
        @DisplayName("Chấp nhận selectedText có độ dài đúng 1000 ký tự")
        void shouldAcceptSelectedTextOfExactMax1000Chars() {
            WikiArticle publishedArticle = createMockArticle(ArticleStatus.PUBLISHED, 1L);
            when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.of(publishedArticle));
            when(clockPort.now()).thenReturn(FIXED_NOW);
            when(contributionRepository.findRecentCandidates(any(), any(), any(), eq(10))).thenReturn(Collections.emptyList());
            when(contributionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            String text1000 = "a".repeat(1000);
            SubmitWikiContributionCommand command = new SubmitWikiContributionCommand(
                    ARTICLE_ID,
                    USER_ID,
                    1L,
                    "TEXT_SELECTION",
                    "WORDING",
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    text1000,
                    null, null, null,
                    Collections.emptyList()
            );

            SubmitWikiContributionResult result = useCase.execute(command);
            assertThat(result.alreadySubmitted()).isFalse();

            ArgumentCaptor<WikiContribution> captor = ArgumentCaptor.forClass(WikiContribution.class);
            verify(contributionRepository).save(captor.capture());
            assertThat(captor.getValue().getSelectedText()).hasSize(1000);
        }

        @Test
        @DisplayName("Ném IllegalArgumentException khi selectedText có độ dài 1001 ký tự (vượt quá 1000)")
        void shouldRejectSelectedTextOfLength1001() {
            WikiArticle publishedArticle = createMockArticle(ArticleStatus.PUBLISHED, 1L);
            when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.of(publishedArticle));
            when(clockPort.now()).thenReturn(FIXED_NOW);

            String text1001 = "a".repeat(1001);
            SubmitWikiContributionCommand command = new SubmitWikiContributionCommand(
                    ARTICLE_ID,
                    USER_ID,
                    1L,
                    "TEXT_SELECTION",
                    "WORDING",
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    text1001,
                    null, null, null,
                    Collections.emptyList()
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("không được vượt quá 1000 ký tự");

            verify(contributionRepository, never()).save(any());
        }

        @Test
        @DisplayName("Kiểm tra giới hạn biên của các trường neo khác: prefix(100/101), suffix(100/101), anchor(255/256)")
        void shouldEnforceAnchorFieldBoundaries() {
            WikiArticle publishedArticle = createMockArticle(ArticleStatus.PUBLISHED, 1L);
            when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.of(publishedArticle));
            when(clockPort.now()).thenReturn(FIXED_NOW);

            // Prefix 101 -> lỗi
            SubmitWikiContributionCommand prefix101 = new SubmitWikiContributionCommand(
                    ARTICLE_ID, USER_ID, 1L, "TEXT_SELECTION", "WORDING",
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    "đoạn trích", "p".repeat(101), null, null, Collections.emptyList()
            );
            assertThatThrownBy(() -> useCase.execute(prefix101))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("không được vượt quá 100 ký tự");

            // Suffix 101 -> lỗi
            SubmitWikiContributionCommand suffix101 = new SubmitWikiContributionCommand(
                    ARTICLE_ID, USER_ID, 1L, "TEXT_SELECTION", "WORDING",
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    "đoạn trích", null, "s".repeat(101), null, Collections.emptyList()
            );
            assertThatThrownBy(() -> useCase.execute(suffix101))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("không được vượt quá 100 ký tự");

            // Heading anchor 256 -> lỗi
            SubmitWikiContributionCommand anchor256 = new SubmitWikiContributionCommand(
                    ARTICLE_ID, USER_ID, 1L, "TEXT_SELECTION", "WORDING",
                    "Nội dung đóng góp hợp lệ có độ dài trên hai mươi ký tự.",
                    "đoạn trích", null, null, "h".repeat(256), Collections.emptyList()
            );
            assertThatThrownBy(() -> useCase.execute(anchor256))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("không được vượt quá 255 ký tự");
        }
    }
}
