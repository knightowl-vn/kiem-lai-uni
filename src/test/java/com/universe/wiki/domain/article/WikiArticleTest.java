package com.universe.wiki.domain.article;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WikiArticleTest {

	private static final UUID ARTICLE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private static final UUID ADMIN_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

	private static final UUID OTHER_ADMIN_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

	private static final Instant CREATED_AT = Instant.parse("2026-08-06T02:00:00Z");

	private static final Instant UPDATED_AT = Instant.parse("2026-08-06T03:00:00Z");

	@Test
	@DisplayName("Tạo bài viết mới ở trạng thái DRAFT")
	void shouldCreateArticleAsDraft() {
		WikiArticle article = createDraft();

		assertThat(article.getId()).isEqualTo(ARTICLE_ID);

		assertThat(article.getTitle()).isEqualTo("Trần Bình An");

		assertThat(article.getSlug().value()).isEqualTo("tran-binh-an");

		assertThat(article.getArticleType()).isEqualTo(ArticleType.CHARACTER);

		assertThat(article.getStatus()).isEqualTo(ArticleStatus.DRAFT);

		assertThat(article.getSummary()).isEmpty();

		assertThat(article.getContent()).isEmpty();

		assertThat(article.getCreatedBy()).isEqualTo(ADMIN_ID);

		assertThat(article.getUpdatedBy()).isEqualTo(ADMIN_ID);

		assertThat(article.getAggregateVersion()).isEqualTo(1L);
	}

	@Test
	@DisplayName("Từ chối tiêu đề bài viết rỗng")
	void shouldRejectBlankTitle() {
		assertThatThrownBy(() -> WikiArticle.createDraft(ARTICLE_ID, " ", new Slug("tran-binh-an"),
				ArticleType.CHARACTER, ADMIN_ID, CREATED_AT)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Tiêu đề bài viết");
	}

	@Test
	@DisplayName("Cập nhật bản nháp thành công")
	void shouldUpdateDraft() {
		WikiArticle article = createDraft();

		article.updateDraft("Trần Bình An", new Slug("tran-binh-an"), ArticleType.CHARACTER,
				"Nhân vật chính của Kiếm Lai.", "Nội dung chi tiết về Trần Bình An.", OTHER_ADMIN_ID, UPDATED_AT);

		assertThat(article.getSummary()).isEqualTo("Nhân vật chính của Kiếm Lai.");

		assertThat(article.getContent()).isEqualTo("Nội dung chi tiết về Trần Bình An.");

		assertThat(article.getUpdatedBy()).isEqualTo(OTHER_ADMIN_ID);

		assertThat(article.getUpdatedAt()).isEqualTo(UPDATED_AT);

		assertThat(article.getAggregateVersion()).isEqualTo(2L);
	}

	@Test
	@DisplayName("Xuất bản bài viết có đầy đủ nội dung")
	void shouldPublishCompleteDraft() {
		WikiArticle article = createCompleteDraft();

		article.publish(ADMIN_ID, UPDATED_AT);

		assertThat(article.getStatus()).isEqualTo(ArticleStatus.PUBLISHED);

		assertThat(article.getPublishedBy()).isEqualTo(ADMIN_ID);

		assertThat(article.getPublishedAt()).isEqualTo(UPDATED_AT);

		assertThat(article.getAggregateVersion()).isEqualTo(3L);
	}


	@Test
	@DisplayName("Không cho xuất bản bài viết chưa có nội dung")
	void shouldRejectPublishingArticleWithoutContent() {
		WikiArticle article = createDraft();

		article.updateDraft("Trần Bình An", new Slug("tran-binh-an"), ArticleType.CHARACTER, "Nhân vật chính.", "",
				ADMIN_ID, UPDATED_AT);

		assertThatThrownBy(() -> article.publish(ADMIN_ID, UPDATED_AT)).isInstanceOf(IllegalStateException.class)
				.hasMessage("Bài viết phải có nội dung trước khi xuất bản.");
	}

	@Test
	@DisplayName("Không cho thay đổi loại bài sau khi đã xuất bản")
	void shouldRejectChangingArticleTypeAfterPublished() {
		WikiArticle article = createCompleteDraft();

		article.publish(ADMIN_ID, UPDATED_AT);

		assertThatThrownBy(() -> article.updateDraft("Trần Bình An", new Slug("tran-binh-an"), ArticleType.FACTION,
				"Tóm tắt.", "Nội dung.", ADMIN_ID, UPDATED_AT.plusSeconds(60)))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("bản nháp");
	}

	@Test
	@DisplayName("Cho phép cập nhật nội dung bài đã xuất bản")
	void shouldAllowUpdatingPublishedContent() {
		WikiArticle article = createCompleteDraft();

		article.publish(ADMIN_ID, UPDATED_AT);

		article.updatePublishedContent("Tóm tắt đã cập nhật.", "Nội dung đã cập nhật.", OTHER_ADMIN_ID,
				UPDATED_AT.plusSeconds(60));

		assertThat(article.getSummary()).isEqualTo("Tóm tắt đã cập nhật.");

		assertThat(article.getContent()).isEqualTo("Nội dung đã cập nhật.");

		assertThat(article.getStatus()).isEqualTo(ArticleStatus.PUBLISHED);

		assertThat(article.getAggregateVersion()).isEqualTo(4L);
	}
	
	@Test
	@DisplayName(
	        "Khôi phục revision cũ thành bản nháp mới"
	)
	void shouldRestoreOldRevisionAsDraft() {
	    WikiArticle article =
	            createCompleteDraft();

	    article.publish(
	            ADMIN_ID,
	            UPDATED_AT
	    );

	    Instant archivedAt =
	            UPDATED_AT.plusSeconds(60);

	    article.archive(
	            ADMIN_ID,
	            archivedAt
	    );

	    long versionBeforeRestore =
	            article.getAggregateVersion();

	    Instant restoredAt =
	            UPDATED_AT.plusSeconds(120);

	    article.restoreAsDraft(
	            "Trần Bình An phiên bản cũ",
	            new Slug(
	                    "tran-binh-an-phien-ban-cu"
	            ),
	            ArticleType.CHARACTER,
	            "Tóm tắt được lấy từ revision cũ.",
	            "Nội dung được lấy từ revision cũ.",
	            OTHER_ADMIN_ID,
	            restoredAt
	    );

	    assertThat(article.getTitle())
	            .isEqualTo(
	                    "Trần Bình An phiên bản cũ"
	            );

	    assertThat(article.getSlug().value())
	            .isEqualTo(
	                    "tran-binh-an-phien-ban-cu"
	            );

	    assertThat(article.getArticleType())
	            .isEqualTo(
	                    ArticleType.CHARACTER
	            );

	    assertThat(article.getSummary())
	            .isEqualTo(
	                    "Tóm tắt được lấy từ revision cũ."
	            );

	    assertThat(article.getContent())
	            .isEqualTo(
	                    "Nội dung được lấy từ revision cũ."
	            );

	    assertThat(article.getStatus())
	            .isEqualTo(
	                    ArticleStatus.DRAFT
	            );

	    assertThat(article.getPublishedBy())
	            .isNull();

	    assertThat(article.getPublishedAt())
	            .isNull();

	    assertThat(article.getArchivedBy())
	            .isNull();

	    assertThat(article.getArchivedAt())
	            .isNull();

	    assertThat(article.getUpdatedBy())
	            .isEqualTo(
	                    OTHER_ADMIN_ID
	            );

	    assertThat(article.getUpdatedAt())
	            .isEqualTo(
	                    restoredAt
	            );

	    assertThat(article.getAggregateVersion())
	            .isEqualTo(
	                    versionBeforeRestore + 1
	            );
	}
	
	@Test
	@DisplayName(
	        "Khôi phục thất bại không làm thay đổi Aggregate"
	)
	void shouldNotMutateArticleWhenRestoreDataIsInvalid() {
	    WikiArticle article =
	            createCompleteDraft();

	    article.publish(
	            ADMIN_ID,
	            UPDATED_AT
	    );

	    article.archive(
	            ADMIN_ID,
	            UPDATED_AT.plusSeconds(60)
	    );

	    String titleBefore =
	            article.getTitle();

	    Slug slugBefore =
	            article.getSlug();

	    ArticleStatus statusBefore =
	            article.getStatus();

	    long versionBefore =
	            article.getAggregateVersion();

	    assertThatThrownBy(() ->
	            article.restoreAsDraft(
	                    "Tên revision cũ",
	                    new Slug(
	                            "ten-revision-cu"
	                    ),
	                    ArticleType.CHARACTER,
	                    "Tóm tắt cũ.",
	                    "Nội dung cũ.",
	                    null,
	                    UPDATED_AT.plusSeconds(120)
	            )
	    )
	            .isInstanceOf(
	                    NullPointerException.class
	            )
	            .hasMessage(
	                    "Người khôi phục không được để trống."
	            );

	    assertThat(article.getTitle())
	            .isEqualTo(
	                    titleBefore
	            );

	    assertThat(article.getSlug())
	            .isEqualTo(
	                    slugBefore
	            );

	    assertThat(article.getStatus())
	            .isEqualTo(
	                    statusBefore
	            );

	    assertThat(article.getAggregateVersion())
	            .isEqualTo(
	                    versionBefore
	            );
	}

	@Test
	@DisplayName("Lưu trữ bài viết thành công")
	void shouldArchiveArticle() {
		WikiArticle article = createCompleteDraft();

		article.publish(ADMIN_ID, UPDATED_AT);

		Instant archivedAt = UPDATED_AT.plusSeconds(120);

		article.archive(OTHER_ADMIN_ID, archivedAt);

		assertThat(article.getStatus()).isEqualTo(ArticleStatus.ARCHIVED);

		assertThat(article.getArchivedBy()).isEqualTo(OTHER_ADMIN_ID);

		assertThat(article.getArchivedAt()).isEqualTo(archivedAt);

		assertThat(article.getAggregateVersion()).isEqualTo(4L);
	}

	@Test
	@DisplayName("Không cho chỉnh sửa bài viết đã lưu trữ")
	void shouldRejectUpdatingArchivedArticle() {
		WikiArticle article = createCompleteDraft();

		article.archive(ADMIN_ID, UPDATED_AT);

		assertThatThrownBy(() -> article.updatePublishedContent("Tóm tắt mới.", "Nội dung mới.", ADMIN_ID,
				UPDATED_AT.plusSeconds(60))).isInstanceOf(IllegalStateException.class)
		.hasMessage(
		        "Chỉ bài viết ở trạng thái PUBLISHED "
		                + "mới được cập nhật theo luồng này."
		);
	}

	private WikiArticle createDraft() {
		return WikiArticle.createDraft(ARTICLE_ID, "Trần Bình An", new Slug("tran-binh-an"), ArticleType.CHARACTER,
				ADMIN_ID, CREATED_AT);
	}

	private WikiArticle createCompleteDraft() {
		WikiArticle article = createDraft();

		article.updateDraft("Trần Bình An", new Slug("tran-binh-an"), ArticleType.CHARACTER,
				"Nhân vật chính của Kiếm Lai.", "Nội dung chi tiết về Trần Bình An.", ADMIN_ID, UPDATED_AT);

		return article;
	}
	
	@Test
	@DisplayName(
	        "Không cho dùng luồng cập nhật published đối với bài DRAFT"
	)
	void shouldRejectPublishedUpdateForDraftArticle() {
	    WikiArticle article =
	            createDraft();

	    assertThatThrownBy(() ->
	            article.updatePublishedContent(
	                    "Tóm tắt mới.",
	                    "Nội dung mới.",
	                    OTHER_ADMIN_ID,
	                    UPDATED_AT
	            )
	    )
	            .isInstanceOf(
	                    IllegalStateException.class
	            )
	            .hasMessage(
	                    "Chỉ bài viết ở trạng thái PUBLISHED "
	                            + "mới được cập nhật theo luồng này."
	            );
	}
	@Test
	@DisplayName(
	        "Tạo bài mới và xuất bản ngay ở aggregate version 1"
	)
	void shouldCreatePublishedArticleAtVersionOne() {
	    UUID articleId =
	            UUID.fromString(
	                    "11111111-1111-1111-1111-111111111111"
	            );

	    UUID adminId =
	            UUID.fromString(
	                    "22222222-2222-2222-2222-222222222222"
	            );

	    Instant now =
	            Instant.parse(
	                    "2026-08-07T10:00:00Z"
	            );

	    WikiArticle article =
	            WikiArticle.createPublished(
	                    articleId,
	                    "Trần Bình An",
	                    new Slug(
	                            "tran-binh-an"
	                    ),
	                    ArticleType.CHARACTER,
	                    "Nhân vật chính của Kiếm Lai.",
	                    "Nội dung đầy đủ của bài viết.",
	                    adminId,
	                    now
	            );

	    assertThat(
	            article.getId()
	    ).isEqualTo(
	            articleId
	    );

	    assertThat(
	            article.getStatus()
	    ).isEqualTo(
	            ArticleStatus.PUBLISHED
	    );

	    assertThat(
	            article.getSummary()
	    ).isEqualTo(
	            "Nhân vật chính của Kiếm Lai."
	    );

	    assertThat(
	            article.getContent()
	    ).isEqualTo(
	            "Nội dung đầy đủ của bài viết."
	    );

	    assertThat(
	            article.getCreatedBy()
	    ).isEqualTo(
	            adminId
	    );

	    assertThat(
	            article.getUpdatedBy()
	    ).isEqualTo(
	            adminId
	    );

	    assertThat(
	            article.getPublishedBy()
	    ).isEqualTo(
	            adminId
	    );

	    assertThat(
	            article.getCreatedAt()
	    ).isEqualTo(
	            now
	    );

	    assertThat(
	            article.getUpdatedAt()
	    ).isEqualTo(
	            now
	    );

	    assertThat(
	            article.getPublishedAt()
	    ).isEqualTo(
	            now
	    );

	    assertThat(
	            article.getAggregateVersion()
	    ).isEqualTo(
	            1L
	    );
	}
	@Test
	@DisplayName(
	        "Cho phép tạo và xuất bản ngay khi summary rỗng"
	)
	void shouldAllowCreatePublishedWithoutSummary() {
	    UUID articleId =
	            UUID.randomUUID();

	    UUID adminId =
	            UUID.randomUUID();

	    Instant now =
	            Instant.parse(
	                    "2026-08-07T10:00:00Z"
	            );

	    WikiArticle article =
	            WikiArticle.createPublished(
	                    articleId,
	                    "Trần Bình An",
	                    new Slug(
	                            "tran-binh-an"
	                    ),
	                    ArticleType.CHARACTER,
	                    "   ",
	                    "Nội dung đầy đủ.",
	                    adminId,
	                    now
	            );

	    assertThat(
	            article.getStatus()
	    ).isEqualTo(
	            ArticleStatus.PUBLISHED
	    );

	    assertThat(
	            article.getSummary()
	    ).isBlank();

	    assertThat(
	            article.getContent()
	    ).isEqualTo(
	            "Nội dung đầy đủ."
	    );

	    assertThat(
	            article.getAggregateVersion()
	    ).isEqualTo(
	            1L
	    );

	    assertThat(
	            article.getCreatedBy()
	    ).isEqualTo(
	            adminId
	    );

	    assertThat(
	            article.getUpdatedBy()
	    ).isEqualTo(
	            adminId
	    );

	    assertThat(
	            article.getPublishedBy()
	    ).isEqualTo(
	            adminId
	    );

	    assertThat(
	            article.getPublishedAt()
	    ).isEqualTo(
	            now
	    );
	}
	@Test
	@DisplayName(
	        "Không cho tạo và xuất bản ngay khi content rỗng"
	)
	void shouldRejectCreatePublishedWithoutContent() {
	    UUID articleId =
	            UUID.randomUUID();

	    UUID adminId =
	            UUID.randomUUID();

	    Instant now =
	            Instant.parse(
	                    "2026-08-07T10:00:00Z"
	            );

	    assertThatThrownBy(() ->
	            WikiArticle.createPublished(
	                    articleId,
	                    "Trần Bình An",
	                    new Slug(
	                            "tran-binh-an"
	                    ),
	                    ArticleType.CHARACTER,
	                    "Nhân vật chính của Kiếm Lai.",
	                    "   ",
	                    adminId,
	                    now
	            )
	    )
	            .isInstanceOf(
	                    IllegalStateException.class
	            )
	            .hasMessage(
	                    "Bài viết phải có nội dung trước khi xuất bản."
	            );
	}
}