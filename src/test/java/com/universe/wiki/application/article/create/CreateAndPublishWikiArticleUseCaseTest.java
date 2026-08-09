package com.universe.wiki.application.article.create;

import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;

import com.universe.wiki.application.exceptions.ArticleSlugAlreadyExistsException;
import com.universe.wiki.application.ports.SlugGeneratorPort;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.application.ports.WikiArticleRevisionRepositoryPort;

import com.universe.wiki.contracts.dto.WikiArticleDTO;
import com.universe.wiki.domain.article.ArticleStatus;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.domain.article.Slug;
import com.universe.wiki.domain.article.WikiArticle;
import com.universe.wiki.domain.revision.WikiArticleRevision;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.any;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateAndPublishWikiArticleUseCaseTest {

	private static final UUID ARTICLE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private static final UUID REVISION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

	private static final UUID ADMIN_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

	private static final Instant NOW = Instant.parse("2026-08-07T10:00:00Z");

	private static final String TITLE = "Trần Bình An";

	private static final String SUMMARY = "Nhân vật chính của Kiếm Lai.";

	private static final String CONTENT = "Nội dung hoàn chỉnh của bài viết.";

	private static final String EDIT_SUMMARY = "Tạo và xuất bản bài Trần Bình An";

	private static final Slug SLUG = new Slug("tran-binh-an");

	@Mock
	private WikiArticleRepositoryPort articleRepositoryPort;

	@Mock
	private WikiArticleRevisionRepositoryPort revisionRepositoryPort;

	@Mock
	private SlugGeneratorPort slugGeneratorPort;

	@Mock
	private IdGeneratorPort idGeneratorPort;

	@Mock
	private ClockPort clockPort;

	private CreateAndPublishWikiArticleUseCase useCase;

	@BeforeEach
	void setUp() {
		useCase = new CreateAndPublishWikiArticleUseCase(articleRepositoryPort, revisionRepositoryPort,
				slugGeneratorPort, idGeneratorPort, clockPort);
	}

	/*
	 * ===================================================== SUCCESS
	 * =====================================================
	 */

	@Test
	@DisplayName("Tạo và xuất bản bài Wiki ngay ở version 1")
	void shouldCreateAndPublishAtVersionOne() {
		prepareSuccessfulCreation();

		WikiArticleDTO result = useCase.execute(createCommand());

		assertThat(result.id()).isEqualTo(ARTICLE_ID);

		assertThat(result.title()).isEqualTo(TITLE);

		assertThat(result.slug()).isEqualTo("tran-binh-an");

		assertThat(result.articleType()).isEqualTo("CHARACTER");

		assertThat(result.summary()).isEqualTo(SUMMARY);

		assertThat(result.content()).isEqualTo(CONTENT);

		assertThat(result.status()).isEqualTo("PUBLISHED");

		assertThat(result.createdBy()).isEqualTo(ADMIN_ID);

		assertThat(result.updatedBy()).isEqualTo(ADMIN_ID);

		assertThat(result.publishedBy()).isEqualTo(ADMIN_ID);

		assertThat(result.createdAt()).isEqualTo(NOW);

		assertThat(result.updatedAt()).isEqualTo(NOW);

		assertThat(result.publishedAt()).isEqualTo(NOW);

		/*
		 * Đây là assertion quan trọng:
		 *
		 * Tạo + Publish ngay chỉ là 1 thao tác nghiệp vụ, nên version phải bắt đầu từ
		 * 1, KHÔNG được thành version 2.
		 */
		assertThat(result.aggregateVersion()).isEqualTo(1L);

		verify(articleRepositoryPort).save(any(WikiArticle.class));

		verify(revisionRepositoryPort).save(any(WikiArticleRevision.class));
	}

	/*
	 * ===================================================== DUPLICATE SLUG
	 * =====================================================
	 */

	@Test
	@DisplayName("Từ chối tạo và xuất bản khi slug đã tồn tại")
	void shouldRejectDuplicateSlug() {
		when(slugGeneratorPort.generate(TITLE)).thenReturn(SLUG);

		when(articleRepositoryPort.existsByArticleTypeAndSlug(ArticleType.CHARACTER, SLUG)).thenReturn(true);

		assertThatThrownBy(() -> useCase.execute(createCommand()))
				.isInstanceOf(ArticleSlugAlreadyExistsException.class);

		verify(articleRepositoryPort, never()).save(any(WikiArticle.class));

		verify(revisionRepositoryPort, never()).save(any(WikiArticleRevision.class));
	}

	/*
	 * ===================================================== PUBLISH VALIDATION
	 * =====================================================
	 */

	@Test
	@DisplayName("Cho phép xuất bản ngay khi summary để trống")
	void shouldAllowBlankSummary() {
		prepareSuccessfulCreation();

		CreateAndPublishWikiArticleCommand command = new CreateAndPublishWikiArticleCommand(TITLE,
				ArticleType.CHARACTER, "   ", CONTENT, EDIT_SUMMARY, ADMIN_ID);

		WikiArticleDTO result = useCase.execute(command);

		assertThat(result).isNotNull();

		assertThat(result.title()).isEqualTo(TITLE);

		assertThat(result.articleType()).isEqualTo("CHARACTER");

		assertThat(result.summary()).isBlank();

		assertThat(result.content()).isEqualTo(CONTENT);

		assertThat(result.status()).isEqualTo("PUBLISHED");

		assertThat(result.aggregateVersion()).isEqualTo(1L);

		verify(articleRepositoryPort).save(any(WikiArticle.class));

		verify(revisionRepositoryPort).save(any(WikiArticleRevision.class));
	}

	@Test
	@DisplayName("Từ chối xuất bản ngay khi content để trống")
	void shouldRejectBlankContent() {
		prepareBeforeCreatingArticle();

		CreateAndPublishWikiArticleCommand command = new CreateAndPublishWikiArticleCommand(TITLE,
				ArticleType.CHARACTER, SUMMARY, "   ", EDIT_SUMMARY, ADMIN_ID);

		assertThatThrownBy(() -> useCase.execute(command)).isInstanceOf(IllegalStateException.class)
				.hasMessage("Bài viết phải có nội dung trước khi xuất bản.");

		verify(articleRepositoryPort, never()).save(any(WikiArticle.class));

		verify(revisionRepositoryPort, never()).save(any(WikiArticleRevision.class));
	}

	/*
	 * ===================================================== TEST HELPERS
	 * =====================================================
	 */

	private void prepareSuccessfulCreation() {
		prepareBeforeCreatingArticle();

		when(idGeneratorPort.generate()).thenReturn(ARTICLE_ID).thenReturn(REVISION_ID);
	}

	private void prepareBeforeCreatingArticle() {
		when(slugGeneratorPort.generate(TITLE)).thenReturn(SLUG);

		when(articleRepositoryPort.existsByArticleTypeAndSlug(ArticleType.CHARACTER, SLUG)).thenReturn(false);

		when(idGeneratorPort.generate()).thenReturn(ARTICLE_ID);

		when(clockPort.now()).thenReturn(NOW);
	}

	private CreateAndPublishWikiArticleCommand createCommand() {
		return new CreateAndPublishWikiArticleCommand(TITLE, ArticleType.CHARACTER, SUMMARY, CONTENT, EDIT_SUMMARY,
				ADMIN_ID);
	}
}