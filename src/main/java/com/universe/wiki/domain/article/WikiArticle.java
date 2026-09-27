package com.universe.wiki.domain.article;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate Root đại diện cho một bài viết trong Wiki.
 *
 * Quản lý: - thông tin chung của bài viết; - vòng đời DRAFT → PUBLISHED →
 * ARCHIVED; - các quy tắc chỉnh sửa và xuất bản; - aggregateVersion: version
 * nghiệp vụ của Aggregate; - contentVersion: version của nội dung bài viết.
 */
public class WikiArticle {

	private static final int MIN_TITLE_LENGTH = 2;

	private static final int MAX_TITLE_LENGTH = 200;

	private static final int MAX_SUMMARY_LENGTH = 1000;

	private static final int MAX_CONTENT_LENGTH = 500_000;

	public static final int DEFAULT_COVER_POSITION = 50;

	public static final int MIN_COVER_POSITION = 0;

	public static final int MAX_COVER_POSITION = 100;

	/*
	 * ===================================================== IDENTITY
	 * =====================================================
	 */

	private final UUID id;

	/*
	 * ===================================================== ARTICLE CONTENT
	 * =====================================================
	 */

	private String title;

	private Slug slug;

	private ArticleType articleType;

	private String summary;

	private String content;

	/*
	 * ===================================================== LIFECYCLE
	 * =====================================================
	 */

	private ArticleStatus status;

	/*
	 * ===================================================== ACTORS
	 * =====================================================
	 */

	private final UUID createdBy;

	private UUID updatedBy;

	private UUID publishedBy;

	private UUID archivedBy;

	/*
	 * ===================================================== TIMESTAMPS
	 * =====================================================
	 */

	private final Instant createdAt;

	private Instant updatedAt;

	private Instant publishedAt;

	private Instant archivedAt;

	/*
	 * ===================================================== COVER MEDIA
	 * =====================================================
	 */

	/**
	 * Reference to optional Media-backed digital asset for article cover.
	 * Wiki stores only this scalar UUID; binary lifecycle is owned by Media.
	 */
	private UUID coverMediaAssetId;

	/**
	 * Horizontal focal position of article cover in percentage (0..100). Default is 50.
	 */
	private int coverPositionX = DEFAULT_COVER_POSITION;

	/**
	 * Vertical focal position of article cover in percentage (0..100). Default is 50.
	 */
	private int coverPositionY = DEFAULT_COVER_POSITION;

	/*
	 * ===================================================== VERSIONS
	 * =====================================================
	 */

	/**
	 * Version nghiệp vụ của Aggregate.
	 *
	 * Tăng sau mọi mutation hợp lệ: - sửa nội dung; - publish; - unpublish; -
	 * archive; - restore.
	 */
	private long aggregateVersion;

	/**
	 * Version nội dung mà Admin nhìn thấy.
	 *
	 * Chỉ tăng khi dữ liệu biên tập thực sự thay đổi: - title; - slug; -
	 * articleType; - summary; - content; - restore sang nội dung khác.
	 *
	 * Publish / Unpublish / Archive không làm tăng.
	 */
	private long contentVersion;

	/*
	 * ===================================================== CONSTRUCTOR
	 * =====================================================
	 */

	private WikiArticle(UUID id, String title, Slug slug, ArticleType articleType, String summary, String content,
			UUID coverMediaAssetId, int coverPositionX, int coverPositionY, ArticleStatus status, UUID createdBy,
			UUID updatedBy, UUID publishedBy, UUID archivedBy, Instant createdAt, Instant updatedAt,
			Instant publishedAt, Instant archivedAt, long aggregateVersion, long contentVersion) {
		this.id = Objects.requireNonNull(id, "Article ID không được để trống.");

		this.title = validateTitle(title);

		this.slug = Objects.requireNonNull(slug, "Slug không được để trống.");

		this.articleType = Objects.requireNonNull(articleType, "Article type không được để trống.");

		this.summary = validateSummary(summary);

		this.content = validateContent(content);

		this.coverMediaAssetId = coverMediaAssetId;

		if (coverMediaAssetId == null) {
			this.coverPositionX = DEFAULT_COVER_POSITION;
			this.coverPositionY = DEFAULT_COVER_POSITION;
		} else {
			this.coverPositionX = validateCoverPosition(coverPositionX, "Vị trí tâm ảnh theo trục X");
			this.coverPositionY = validateCoverPosition(coverPositionY, "Vị trí tâm ảnh theo trục Y");
		}

		this.status = Objects.requireNonNull(status, "Article status không được để trống.");

		this.createdBy = Objects.requireNonNull(createdBy, "Người tạo bài viết không được để trống.");

		this.updatedBy = updatedBy;

		this.publishedBy = publishedBy;

		this.archivedBy = archivedBy;

		this.createdAt = Objects.requireNonNull(createdAt, "Thời gian tạo bài viết không được để trống.");

		this.updatedAt = Objects.requireNonNull(updatedAt, "Thời gian cập nhật bài viết không được để trống.");

		this.publishedAt = publishedAt;

		this.archivedAt = archivedAt;

		if (aggregateVersion < 1L) {
			throw new IllegalArgumentException("Aggregate version phải lớn hơn hoặc bằng 1.");
		}

		if (contentVersion < 1L) {
			throw new IllegalArgumentException("Content version phải lớn hơn hoặc bằng 1.");
		}

		this.aggregateVersion = aggregateVersion;

		this.contentVersion = contentVersion;
	}

	/*
	 * ===================================================== FACTORY - CREATE DRAFT
	 * =====================================================
	 */

	/**
	 * Tạo bản nháp rỗng.
	 *
	 * Giữ lại để tương thích với các caller cũ.
	 */
	public static WikiArticle createDraft(UUID id, String title, Slug slug, ArticleType articleType, UUID createdBy,
			Instant now) {
		return createDraft(id, title, slug, articleType, "", "", null, DEFAULT_COVER_POSITION, DEFAULT_COVER_POSITION,
				createdBy, now);
	}

	/**
	 * Tạo bản nháp rỗng với ảnh bìa tùy chọn.
	 */
	public static WikiArticle createDraft(UUID id, String title, Slug slug, ArticleType articleType, UUID createdBy,
			Instant now, UUID coverMediaAssetId) {
		return createDraft(id, title, slug, articleType, "", "", coverMediaAssetId, DEFAULT_COVER_POSITION,
				DEFAULT_COVER_POSITION, createdBy, now);
	}

	/**
	 * Tạo một bài viết mới ở trạng thái DRAFT cùng với nội dung ban đầu, ảnh bìa và tọa độ tâm ảnh tùy chọn.
	 */
	public static WikiArticle createDraft(UUID id, String title, Slug slug, ArticleType articleType, String summary,
			String content, UUID coverMediaAssetId, Integer coverPositionX, Integer coverPositionY, UUID createdBy,
			Instant now) {
		Instant normalizedNow = Objects.requireNonNull(now, "Thời gian tạo bài viết không được để trống.");
		int posX = validateCoverPosition(coverPositionX, "Vị trí tâm ảnh theo trục X");
		int posY = validateCoverPosition(coverPositionY, "Vị trí tâm ảnh theo trục Y");

		return new WikiArticle(id, title, slug, articleType, summary, content, coverMediaAssetId, posX, posY,
				ArticleStatus.DRAFT, createdBy, createdBy, null, null, normalizedNow, normalizedNow, null, null,
				1L, 1L);
	}

	/**
	 * Tạo một bài viết mới ở trạng thái DRAFT cùng với nội dung ban đầu và ảnh bìa tùy chọn (mặc định 50/50).
	 */
	public static WikiArticle createDraft(UUID id, String title, Slug slug, ArticleType articleType, String summary,
			String content, UUID coverMediaAssetId, UUID createdBy, Instant now) {
		return createDraft(id, title, slug, articleType, summary, content, coverMediaAssetId, DEFAULT_COVER_POSITION,
				DEFAULT_COVER_POSITION, createdBy, now);
	}

	/**
	 * Tạo một bài viết mới ở trạng thái DRAFT cùng với nội dung ban đầu (không có ảnh bìa).
	 */
	public static WikiArticle createDraft(UUID id, String title, Slug slug, ArticleType articleType, String summary,
			String content, UUID createdBy, Instant now) {
		return createDraft(id, title, slug, articleType, summary, content, null, DEFAULT_COVER_POSITION,
				DEFAULT_COVER_POSITION, createdBy, now);
	}

	/*
	 * ===================================================== FACTORY - CREATE AND
	 * PUBLISH =====================================================
	 */

	/**
	 * Tạo một bài viết mới và xuất bản ngay với ảnh bìa và tọa độ tâm ảnh tùy chọn.
	 *
	 * Đây là một thao tác nghiệp vụ duy nhất, không tạo một DRAFT được lưu trung gian.
	 */
	public static WikiArticle createPublished(UUID id, String title, Slug slug, ArticleType articleType, String summary,
			String content, UUID coverMediaAssetId, Integer coverPositionX, Integer coverPositionY, UUID createdBy,
			Instant now) {
		UUID normalizedCreatedBy = Objects.requireNonNull(createdBy, "Người tạo bài viết không được để trống.");
		Instant normalizedNow = Objects.requireNonNull(now, "Thời gian tạo bài viết không được để trống.");
		int posX = validateCoverPosition(coverPositionX, "Vị trí tâm ảnh theo trục X");
		int posY = validateCoverPosition(coverPositionY, "Vị trí tâm ảnh theo trục Y");

		WikiArticle article = new WikiArticle(id, title, slug, articleType, summary, content, coverMediaAssetId, posX,
				posY, ArticleStatus.PUBLISHED, normalizedCreatedBy, normalizedCreatedBy, normalizedCreatedBy, null,
				normalizedNow, normalizedNow, normalizedNow, null, 1L, 1L);

		/*
		 * Publish yêu cầu content. Summary là tùy chọn.
		 */
		article.requirePublishableContent();

		return article;
	}

	/**
	 * Tạo một bài viết mới và xuất bản ngay với ảnh bìa tùy chọn (mặc định 50/50).
	 */
	public static WikiArticle createPublished(UUID id, String title, Slug slug, ArticleType articleType, String summary,
			String content, UUID coverMediaAssetId, UUID createdBy, Instant now) {
		return createPublished(id, title, slug, articleType, summary, content, coverMediaAssetId, DEFAULT_COVER_POSITION,
				DEFAULT_COVER_POSITION, createdBy, now);
	}

	/**
	 * Tạo một bài viết mới và xuất bản ngay (không có ảnh bìa).
	 */
	public static WikiArticle createPublished(UUID id, String title, Slug slug, ArticleType articleType, String summary,
			String content, UUID createdBy, Instant now) {
		return createPublished(id, title, slug, articleType, summary, content, null, DEFAULT_COVER_POSITION,
				DEFAULT_COVER_POSITION, createdBy, now);
	}

	/*
	 * ===================================================== REHYDRATE
	 * =====================================================
	 */

	/**
	 * Khôi phục Aggregate từ persistence với coverMediaAssetId và tọa độ tâm ảnh.
	 *
	 * Không sinh ra mutation nghiệp vụ mới.
	 */
	public static WikiArticle rehydrate(UUID id, String title, Slug slug, ArticleType articleType, String summary,
			String content, UUID coverMediaAssetId, int coverPositionX, int coverPositionY, ArticleStatus status,
			UUID createdBy, UUID updatedBy, UUID publishedBy, UUID archivedBy, Instant createdAt, Instant updatedAt,
			Instant publishedAt, Instant archivedAt, long aggregateVersion, long contentVersion) {
		return new WikiArticle(id, title, slug, articleType, summary, content, coverMediaAssetId, coverPositionX,
				coverPositionY, status, createdBy, updatedBy, publishedBy, archivedBy, createdAt, updatedAt,
				publishedAt, archivedAt, aggregateVersion, contentVersion);
	}

	/**
	 * Khôi phục Aggregate từ persistence với coverMediaAssetId (mặc định 50/50).
	 */
	public static WikiArticle rehydrate(UUID id, String title, Slug slug, ArticleType articleType, String summary,
			String content, UUID coverMediaAssetId, ArticleStatus status, UUID createdBy, UUID updatedBy,
			UUID publishedBy, UUID archivedBy, Instant createdAt, Instant updatedAt, Instant publishedAt,
			Instant archivedAt, long aggregateVersion, long contentVersion) {
		return rehydrate(id, title, slug, articleType, summary, content, coverMediaAssetId, DEFAULT_COVER_POSITION,
				DEFAULT_COVER_POSITION, status, createdBy, updatedBy, publishedBy, archivedBy, createdAt, updatedAt,
				publishedAt, archivedAt, aggregateVersion, contentVersion);
	}

	/**
	 * Khôi phục Aggregate từ persistence (caller cũ không có coverMediaAssetId).
	 */
	public static WikiArticle rehydrate(UUID id, String title, Slug slug, ArticleType articleType, String summary,
			String content, ArticleStatus status, UUID createdBy, UUID updatedBy, UUID publishedBy, UUID archivedBy,
			Instant createdAt, Instant updatedAt, Instant publishedAt, Instant archivedAt, long aggregateVersion,
			long contentVersion) {
		return rehydrate(id, title, slug, articleType, summary, content, null, DEFAULT_COVER_POSITION,
				DEFAULT_COVER_POSITION, status, createdBy, updatedBy, publishedBy, archivedBy, createdAt, updatedAt,
				publishedAt, archivedAt, aggregateVersion, contentVersion);
	}

	/*
	 * ===================================================== UPDATE DRAFT
	 * =====================================================
	 */

	/**
	 * Cập nhật toàn bộ thông tin của bản nháp.
	 *
	 * Trả về: - true: dữ liệu thực sự thay đổi; - false: dữ liệu mới giống hoàn
	 * toàn dữ liệu hiện tại.
	 *
	 * Khi không có thay đổi: - không tăng aggregateVersion; - không tăng
	 * contentVersion; - không đổi updatedBy / updatedAt.
	 */
	public boolean updateDraft(String title, Slug slug, ArticleType articleType, String summary, String content,
			UUID coverMediaAssetId, Integer coverPositionX, Integer coverPositionY, UUID editorId, Instant now) {
		requireStatus(ArticleStatus.DRAFT, "Chỉ được thay đổi tiêu đề, slug và loại bài khi bài viết còn là bản nháp.");

		/*
		 * Validate toàn bộ dữ liệu trước khi mutate Aggregate.
		 */
		String normalizedTitle = validateTitle(title);

		Slug normalizedSlug = Objects.requireNonNull(slug, "Slug không được để trống.");

		ArticleType normalizedArticleType = Objects.requireNonNull(articleType, "Article type không được để trống.");

		String normalizedSummary = validateSummary(summary);

		String normalizedContent = validateContent(content);

		UUID normalizedEditorId = Objects.requireNonNull(editorId, "Người cập nhật không được để trống.");

		Instant normalizedNow = Objects.requireNonNull(now, "Thời gian cập nhật không được để trống.");

		int posX = (coverMediaAssetId == null)
				? DEFAULT_COVER_POSITION
				: validateCoverPosition(coverPositionX != null ? coverPositionX : (Objects.equals(this.coverMediaAssetId, coverMediaAssetId) ? this.coverPositionX : DEFAULT_COVER_POSITION), "Vị trí tâm ảnh theo trục X");
		int posY = (coverMediaAssetId == null)
				? DEFAULT_COVER_POSITION
				: validateCoverPosition(coverPositionY != null ? coverPositionY : (Objects.equals(this.coverMediaAssetId, coverMediaAssetId) ? this.coverPositionY : DEFAULT_COVER_POSITION), "Vị trí tâm ảnh theo trục Y");

		boolean contentChanged = hasEditorialContentChanged(normalizedTitle, normalizedSlug, normalizedArticleType,
				normalizedSummary, normalizedContent);
		boolean coverChanged = !Objects.equals(this.coverMediaAssetId, coverMediaAssetId);
		boolean positionChanged = this.coverPositionX != posX || this.coverPositionY != posY;

		/*
		 * Save mà không sửa gì (cả nội dung lẫn ảnh bìa và vị trí tâm) thì không tạo version mới.
		 */
		if (!contentChanged && !coverChanged && !positionChanged) {
			return false;
		}

		this.title = normalizedTitle;
		this.slug = normalizedSlug;
		this.articleType = normalizedArticleType;
		this.summary = normalizedSummary;
		this.content = normalizedContent;
		this.coverMediaAssetId = coverMediaAssetId;
		this.coverPositionX = posX;
		this.coverPositionY = posY;
		this.updatedBy = normalizedEditorId;
		this.updatedAt = normalizedNow;

		increaseVersion();
		if (contentChanged) {
			increaseContentVersion();
		}

		return true;
	}

	public boolean updateDraft(String title, Slug slug, ArticleType articleType, String summary, String content,
			UUID coverMediaAssetId, UUID editorId, Instant now) {
		return updateDraft(title, slug, articleType, summary, content, coverMediaAssetId, this.coverPositionX,
				this.coverPositionY, editorId, now);
	}

	public boolean updateDraft(String title, Slug slug, ArticleType articleType, String summary, String content,
			UUID editorId, Instant now) {
		return updateDraft(title, slug, articleType, summary, content, this.coverMediaAssetId, this.coverPositionX,
				this.coverPositionY, editorId, now);
	}

	/*
	 * ===================================================== UPDATE DRAFT + PUBLISH
	 * =====================================================
	 */

	/**
	 * Cập nhật bản nháp và xuất bản trong cùng một hành động nghiệp vụ.
	 *
	 * Trả về: - true : nội dung biên tập thực sự thay đổi; - false : dữ liệu biên
	 * tập giữ nguyên, chỉ publish.
	 *
	 * aggregateVersion: - luôn tăng đúng 1 vì đây là một business action.
	 *
	 * contentVersion: - chỉ tăng khi nội dung biên tập thực sự thay đổi.
	 */
	public boolean updateDraftAndPublish(String title, Slug slug, ArticleType articleType, String summary,
			String content, UUID coverMediaAssetId, Integer coverPositionX, Integer coverPositionY, UUID actorId,
			Instant now) {
		requireStatus(ArticleStatus.DRAFT, "Chỉ bài viết ở trạng thái DRAFT " + "mới được cập nhật và xuất bản.");

		/*
		 * Validate toàn bộ trước khi mutate.
		 */
		String normalizedTitle = validateTitle(title);

		Slug normalizedSlug = Objects.requireNonNull(slug, "Slug không được để trống.");

		ArticleType normalizedArticleType = Objects.requireNonNull(articleType, "Article type không được để trống.");

		String normalizedSummary = validateSummary(summary);

		String normalizedContent = validateContent(content);

		UUID normalizedActorId = Objects.requireNonNull(actorId,
				"Người cập nhật và xuất bản " + "không được để trống.");

		Instant normalizedNow = Objects.requireNonNull(now, "Thời gian cập nhật và xuất bản " + "không được để trống.");

		/*
		 * Publish bắt buộc phải có content.
		 *
		 * Kiểm tra TRƯỚC khi mutate aggregate.
		 */
		if (normalizedContent.isBlank()) {
			throw new IllegalStateException("Bài viết phải có nội dung trước khi xuất bản.");
		}

		int posX = (coverMediaAssetId == null)
				? DEFAULT_COVER_POSITION
				: validateCoverPosition(coverPositionX != null ? coverPositionX : (Objects.equals(this.coverMediaAssetId, coverMediaAssetId) ? this.coverPositionX : DEFAULT_COVER_POSITION), "Vị trí tâm ảnh theo trục X");
		int posY = (coverMediaAssetId == null)
				? DEFAULT_COVER_POSITION
				: validateCoverPosition(coverPositionY != null ? coverPositionY : (Objects.equals(this.coverMediaAssetId, coverMediaAssetId) ? this.coverPositionY : DEFAULT_COVER_POSITION), "Vị trí tâm ảnh theo trục Y");

		boolean contentChanged = hasEditorialContentChanged(normalizedTitle, normalizedSlug, normalizedArticleType,
				normalizedSummary, normalizedContent);

		/*
		 * Apply editorial state.
		 */
		this.title = normalizedTitle;

		this.slug = normalizedSlug;

		this.articleType = normalizedArticleType;

		this.summary = normalizedSummary;

		this.content = normalizedContent;

		this.coverMediaAssetId = coverMediaAssetId;

		this.coverPositionX = posX;

		this.coverPositionY = posY;

		/*
		 * Apply lifecycle state.
		 */
		this.status = ArticleStatus.PUBLISHED;

		this.publishedBy = normalizedActorId;

		this.publishedAt = normalizedNow;

		this.archivedBy = null;

		this.archivedAt = null;

		this.updatedBy = normalizedActorId;

		this.updatedAt = normalizedNow;

		/*
		 * Chỉ nội dung thực sự đổi mới tạo content version mới.
		 */
		if (contentChanged) {
			increaseContentVersion();
		}

		/*
		 * Một click "Lưu & xuất bản" = một mutation nghiệp vụ.
		 */
		increaseVersion();

		return contentChanged;
	}

	public boolean updateDraftAndPublish(String title, Slug slug, ArticleType articleType, String summary,
			String content, UUID coverMediaAssetId, UUID actorId, Instant now) {
		return updateDraftAndPublish(title, slug, articleType, summary, content, coverMediaAssetId,
				this.coverPositionX, this.coverPositionY, actorId, now);
	}

	public boolean updateDraftAndPublish(String title, Slug slug, ArticleType articleType, String summary,
			String content, UUID actorId, Instant now) {
		return updateDraftAndPublish(title, slug, articleType, summary, content, this.coverMediaAssetId,
				this.coverPositionX, this.coverPositionY, actorId, now);
	}

	/*
	 * ===================================================== UPDATE PUBLISHED
	 * =====================================================
	 */

	/**
	 * Cập nhật summary/content của bài đã PUBLISHED cùng với ảnh bìa và tọa độ tâm ảnh tùy chọn.
	 *
	 * Tiêu đề, slug và articleType không thay đổi trong flow này.
	 */
	public boolean updatePublishedContent(String summary, String content, UUID coverMediaAssetId,
			Integer coverPositionX, Integer coverPositionY, UUID editorId, Instant now) {
		requireStatus(ArticleStatus.PUBLISHED, "Chỉ bài viết ở trạng thái PUBLISHED mới được cập nhật theo luồng này.");

		String normalizedSummary = validateSummary(summary);

		String normalizedContent = validateContent(content);

		if (normalizedContent.isBlank()) {
			throw new IllegalStateException("Bài viết đã xuất bản phải có nội dung.");
		}

		UUID normalizedEditorId = Objects.requireNonNull(editorId, "Người cập nhật không được để trống.");

		Instant normalizedNow = Objects.requireNonNull(now, "Thời gian cập nhật không được để trống.");

		int posX = (coverMediaAssetId == null)
				? DEFAULT_COVER_POSITION
				: validateCoverPosition(coverPositionX != null ? coverPositionX : (Objects.equals(this.coverMediaAssetId, coverMediaAssetId) ? this.coverPositionX : DEFAULT_COVER_POSITION), "Vị trí tâm ảnh theo trục X");
		int posY = (coverMediaAssetId == null)
				? DEFAULT_COVER_POSITION
				: validateCoverPosition(coverPositionY != null ? coverPositionY : (Objects.equals(this.coverMediaAssetId, coverMediaAssetId) ? this.coverPositionY : DEFAULT_COVER_POSITION), "Vị trí tâm ảnh theo trục Y");

		boolean contentChanged = !Objects.equals(this.summary, normalizedSummary)
				|| !Objects.equals(this.content, normalizedContent);
		boolean coverChanged = !Objects.equals(this.coverMediaAssetId, coverMediaAssetId);
		boolean positionChanged = this.coverPositionX != posX || this.coverPositionY != posY;

		if (!contentChanged && !coverChanged && !positionChanged) {
			return false;
		}

		this.summary = normalizedSummary;

		this.content = normalizedContent;

		this.coverMediaAssetId = coverMediaAssetId;

		this.coverPositionX = posX;

		this.coverPositionY = posY;

		this.updatedBy = normalizedEditorId;

		this.updatedAt = normalizedNow;

		increaseVersion();
		if (contentChanged) {
			increaseContentVersion();
		}

		return true;
	}

	public boolean updatePublishedContent(String summary, String content, UUID coverMediaAssetId, UUID editorId, Instant now) {
		return updatePublishedContent(summary, content, coverMediaAssetId, this.coverPositionX, this.coverPositionY,
				editorId, now);
	}

	public boolean updatePublishedContent(String summary, String content, UUID editorId, Instant now) {
		return updatePublishedContent(summary, content, this.coverMediaAssetId, this.coverPositionX,
				this.coverPositionY, editorId, now);
	}

	/*
	 * ===================================================== COVER REFERENCE MUTATION
	 * =====================================================
	 */

	/**
	 * Thay đổi tham chiếu ảnh bìa Media của bài viết (gắn mới, gỡ bỏ, đổi sang asset khác, hoặc đổi tâm ảnh).
	 *
	 * - null -> UUID (gắn mới): aggregateVersion tăng, contentVersion giữ nguyên.
	 * - UUID -> null (gỡ bỏ): aggregateVersion tăng, contentVersion giữ nguyên, tâm ảnh reset về 50/50.
	 * - UUID1 -> UUID2 (đổi asset): aggregateVersion tăng, contentVersion giữ nguyên.
	 * - cùng UUID và cùng tâm ảnh: trả về false, không tạo mutation giả.
	 * - cùng UUID nhưng đổi tâm ảnh: aggregateVersion tăng, contentVersion giữ nguyên.
	 */
	public boolean changeCoverMedia(UUID newCoverMediaAssetId, Integer newCoverPositionX, Integer newCoverPositionY,
			UUID editorId, Instant now) {
		int posX = (newCoverMediaAssetId == null)
				? DEFAULT_COVER_POSITION
				: validateCoverPosition(newCoverPositionX != null ? newCoverPositionX : (Objects.equals(this.coverMediaAssetId, newCoverMediaAssetId) ? this.coverPositionX : DEFAULT_COVER_POSITION), "Vị trí tâm ảnh theo trục X");
		int posY = (newCoverMediaAssetId == null)
				? DEFAULT_COVER_POSITION
				: validateCoverPosition(newCoverPositionY != null ? newCoverPositionY : (Objects.equals(this.coverMediaAssetId, newCoverMediaAssetId) ? this.coverPositionY : DEFAULT_COVER_POSITION), "Vị trí tâm ảnh theo trục Y");

		boolean coverChanged = !Objects.equals(this.coverMediaAssetId, newCoverMediaAssetId);
		boolean positionChanged = this.coverPositionX != posX || this.coverPositionY != posY;

		if (!coverChanged && !positionChanged) {
			return false;
		}

		UUID normalizedEditorId = Objects.requireNonNull(editorId, "Người cập nhật không được để trống.");
		Instant normalizedNow = Objects.requireNonNull(now, "Thời gian cập nhật không được để trống.");

		this.coverMediaAssetId = newCoverMediaAssetId;
		this.coverPositionX = posX;
		this.coverPositionY = posY;
		this.updatedBy = normalizedEditorId;
		this.updatedAt = normalizedNow;

		increaseVersion();
		return true;
	}

	public boolean changeCoverMediaAssetId(UUID newCoverMediaAssetId, UUID editorId, Instant now) {
		return changeCoverMedia(newCoverMediaAssetId, this.coverPositionX, this.coverPositionY, editorId, now);
	}

	public boolean changeCoverPosition(int newCoverPositionX, int newCoverPositionY, UUID editorId, Instant now) {
		return changeCoverMedia(this.coverMediaAssetId, newCoverPositionX, newCoverPositionY, editorId, now);
	}

	public UUID getCoverMediaAssetId() {
		return coverMediaAssetId;
	}

	public int getCoverPositionX() {
		return coverPositionX;
	}

	public int getCoverPositionY() {
		return coverPositionY;
	}

	/*
	 * ===================================================== PUBLISH
	 * =====================================================
	 */

	/**
	 * Xuất bản bài viết.
	 *
	 * Đây là lifecycle mutation: - aggregateVersion tăng; - contentVersion giữ
	 * nguyên.
	 */
	public void publish(UUID publisherId, Instant now) {
		requireStatus(ArticleStatus.DRAFT, "Chỉ bài viết ở trạng thái DRAFT mới được xuất bản.");

		requirePublishableContent();

		UUID normalizedPublisherId = Objects.requireNonNull(publisherId, "Người xuất bản không được để trống.");

		Instant normalizedNow = Objects.requireNonNull(now, "Thời gian xuất bản không được để trống.");

		this.status = ArticleStatus.PUBLISHED;

		this.publishedBy = normalizedPublisherId;

		this.publishedAt = normalizedNow;

		this.updatedBy = normalizedPublisherId;

		this.updatedAt = normalizedNow;

		/*
		 * Publish không làm thay đổi nội dung.
		 */
		increaseVersion();
	}

	/*
	 * ===================================================== UNPUBLISH
	 * =====================================================
	 */

	/**
	 * Gỡ một bài đã xuất bản trở lại DRAFT.
	 *
	 * Nội dung được giữ nguyên.
	 *
	 * - aggregateVersion tăng; - contentVersion giữ nguyên.
	 */
	public void unpublish(UUID actorId, Instant now) {
		requireStatus(ArticleStatus.PUBLISHED, "Chỉ bài viết ở trạng thái PUBLISHED mới được gỡ xuất bản.");

		UUID normalizedActorId = Objects.requireNonNull(actorId, "Người gỡ xuất bản không được để trống.");

		Instant normalizedNow = Objects.requireNonNull(now, "Thời gian gỡ xuất bản không được để trống.");

		this.status = ArticleStatus.DRAFT;

		/*
		 * Không còn là lần publish hiện tại.
		 *
		 * Lịch sử publish cũ vẫn nằm trong revisions.
		 */
		this.publishedBy = null;

		this.publishedAt = null;

		this.updatedBy = normalizedActorId;

		this.updatedAt = normalizedNow;

		/*
		 * Gỡ publish không liên quan archive.
		 */
		this.archivedBy = null;

		this.archivedAt = null;

		/*
		 * Không tăng contentVersion.
		 */
		increaseVersion();
	}

	/*
	 * ===================================================== RESTORE
	 * =====================================================
	 */

	/**
	 * Khôi phục dữ liệu từ một revision cũ thành DRAFT.
	 *
	 * Nếu dữ liệu được restore khác dữ liệu hiện tại: - contentVersion tăng 1.
	 *
	 * Không quay contentVersion về version lịch sử cũ.
	 *
	 * Ví dụ: current content v5 restore dữ liệu từ revision thuộc content v2 →
	 * content hiện tại trở thành v6.
	 */
	public void restoreAsDraft(String title, Slug slug, ArticleType articleType, String summary, String content,
			UUID actorId, Instant now) {
		String normalizedTitle = validateTitle(title);

		Slug normalizedSlug = Objects.requireNonNull(slug, "Slug không được để trống.");

		ArticleType normalizedArticleType = Objects.requireNonNull(articleType, "Article type không được để trống.");

		String normalizedSummary = validateSummary(summary);

		String normalizedContent = validateContent(content);

		UUID normalizedActorId = Objects.requireNonNull(actorId, "Người khôi phục không được để trống.");

		Instant normalizedNow = Objects.requireNonNull(now, "Thời gian khôi phục không được để trống.");

		/*
		 * Kiểm tra nội dung trước khi ghi đè.
		 */
		boolean contentChanged = hasEditorialContentChanged(normalizedTitle, normalizedSlug, normalizedArticleType,
				normalizedSummary, normalizedContent);

		this.title = normalizedTitle;

		this.slug = normalizedSlug;

		this.articleType = normalizedArticleType;

		this.summary = normalizedSummary;

		this.content = normalizedContent;

		/*
		 * Restore luôn đưa bài về DRAFT.
		 */
		this.status = ArticleStatus.DRAFT;

		this.publishedBy = null;

		this.publishedAt = null;

		this.archivedBy = null;

		this.archivedAt = null;

		this.updatedBy = normalizedActorId;

		this.updatedAt = normalizedNow;

		/*
		 * Chỉ nội dung khác mới tạo content version mới.
		 */
		if (contentChanged) {
			increaseContentVersion();
		}

		/*
		 * Restore vẫn là một mutation nghiệp vụ, nên aggregateVersion luôn tăng.
		 */
		increaseVersion();
	}

	/*
	 * ===================================================== ARCHIVE
	 * =====================================================
	 */

	/**
	 * Lưu trữ bài viết.
	 *
	 * Đây là lifecycle mutation: - aggregateVersion tăng; - contentVersion giữ
	 * nguyên.
	 */
	public void archive(UUID actorId, Instant now) {
		if (status == ArticleStatus.ARCHIVED) {
			throw new IllegalStateException("Bài viết đã ở trạng thái ARCHIVED.");
		}

		UUID normalizedActorId = Objects.requireNonNull(actorId, "Người lưu trữ bài viết không được để trống.");

		Instant normalizedNow = Objects.requireNonNull(now, "Thời gian lưu trữ bài viết không được để trống.");

		this.status = ArticleStatus.ARCHIVED;

		this.archivedBy = normalizedActorId;

		this.archivedAt = normalizedNow;

		this.updatedBy = normalizedActorId;

		this.updatedAt = normalizedNow;

		/*
		 * Archive không thay đổi nội dung.
		 */
		increaseVersion();
	}

	/*
	 * ===================================================== DELETE RULE
	 * =====================================================
	 */

	/**
	 * DRAFT và ARCHIVED có thể xóa.
	 *
	 * PUBLISHED phải được gỡ xuất bản trước.
	 */
	public void ensureCanBeDeleted() {
		if (status == ArticleStatus.PUBLISHED) {
			throw new IllegalStateException(
					"Không thể xóa bài viết đang PUBLISHED. " + "Hãy gỡ xuất bản bài viết trước khi xóa.");
		}
	}

	/*
	 * ===================================================== DOMAIN VALIDATION
	 * =====================================================
	 */

	private void requirePublishableContent() {
		if (content == null || content.isBlank()) {
			throw new IllegalStateException("Bài viết phải có nội dung trước khi xuất bản.");
		}
	}

	private void requireStatus(ArticleStatus requiredStatus, String errorMessage) {
		if (status != requiredStatus) {
			throw new IllegalStateException(errorMessage);
		}
	}

	/*
	 * ===================================================== CHANGE DETECTION
	 * =====================================================
	 */

	/**
	 * Kiểm tra dữ liệu biên tập có thay đổi hay không.
	 */
	private boolean hasEditorialContentChanged(String newTitle, Slug newSlug, ArticleType newArticleType,
			String newSummary, String newContent) {
		return !Objects.equals(this.title, newTitle) || !Objects.equals(this.slug.value(), newSlug.value())
				|| this.articleType != newArticleType || !Objects.equals(this.summary, newSummary)
				|| !Objects.equals(this.content, newContent);
	}

	/*
	 * ===================================================== VERSION MUTATION
	 * =====================================================
	 */

	/**
	 * Dùng cho thay đổi nội dung thật sự.
	 *
	 * Một content edit đồng thời: - thay đổi Aggregate; - tạo content version mới.
	 */
	private void markContentUpdated(UUID editorId, Instant now) {
		this.updatedBy = Objects.requireNonNull(editorId, "Người cập nhật không được để trống.");

		this.updatedAt = Objects.requireNonNull(now, "Thời gian cập nhật không được để trống.");

		increaseVersion();

		increaseContentVersion();
	}

	private void increaseVersion() {
		this.aggregateVersion++;
	}

	private void increaseContentVersion() {
		this.contentVersion++;
	}

	/*
	 * ===================================================== VALUE VALIDATION
	 * =====================================================
	 */

	private static String validateTitle(String title) {
		if (title == null) {
			throw new IllegalArgumentException("Tiêu đề bài viết không được để trống.");
		}

		String normalizedTitle = title.trim();

		if (normalizedTitle.length() < MIN_TITLE_LENGTH) {
			throw new IllegalArgumentException("Tiêu đề bài viết phải có ít nhất " + MIN_TITLE_LENGTH + " ký tự.");
		}

		if (normalizedTitle.length() > MAX_TITLE_LENGTH) {
			throw new IllegalArgumentException("Tiêu đề bài viết không được vượt quá " + MAX_TITLE_LENGTH + " ký tự.");
		}

		return normalizedTitle;
	}

	private static String validateSummary(String summary) {
		if (summary == null) {
			return "";
		}

		String normalizedSummary = summary.trim();

		if (normalizedSummary.length() > MAX_SUMMARY_LENGTH) {
			throw new IllegalArgumentException("Phần tóm tắt không được vượt quá " + MAX_SUMMARY_LENGTH + " ký tự.");
		}

		return normalizedSummary;
	}

	private static String validateContent(String content) {
		if (content == null) {
			return "";
		}

		String normalizedContent = content.trim();

		if (normalizedContent.length() > MAX_CONTENT_LENGTH) {
			throw new IllegalArgumentException(
					"Nội dung bài viết không được vượt quá " + MAX_CONTENT_LENGTH + " ký tự.");
		}

		return normalizedContent;
	}

	public static int validateCoverPosition(Integer position, String fieldName) {
		if (position == null) {
			return DEFAULT_COVER_POSITION;
		}
		if (position < MIN_COVER_POSITION || position > MAX_COVER_POSITION) {
			throw new IllegalArgumentException(
					fieldName + " phải nằm trong khoảng từ " + MIN_COVER_POSITION + " đến " + MAX_COVER_POSITION + ".");
		}
		return position;
	}

	/*
	 * ===================================================== GETTERS
	 * =====================================================
	 */

	public UUID getId() {
		return id;
	}

	public String getTitle() {
		return title;
	}

	public Slug getSlug() {
		return slug;
	}

	public ArticleType getArticleType() {
		return articleType;
	}

	public String getSummary() {
		return summary;
	}

	public String getContent() {
		return content;
	}

	public ArticleStatus getStatus() {
		return status;
	}

	public UUID getCreatedBy() {
		return createdBy;
	}

	public UUID getUpdatedBy() {
		return updatedBy;
	}

	public UUID getPublishedBy() {
		return publishedBy;
	}

	public UUID getArchivedBy() {
		return archivedBy;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public Instant getPublishedAt() {
		return publishedAt;
	}

	public Instant getArchivedAt() {
		return archivedAt;
	}

	public long getAggregateVersion() {
		return aggregateVersion;
	}

	public long getContentVersion() {
		return contentVersion;
	}
}