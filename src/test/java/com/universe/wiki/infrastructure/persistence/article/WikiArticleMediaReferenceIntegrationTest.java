package com.universe.wiki.infrastructure.persistence.article;

import com.universe.test.TestDatabaseSupport;
import com.universe.wiki.domain.article.ArticleStatus;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.domain.article.Slug;
import com.universe.wiki.domain.article.WikiArticle;
import com.universe.wiki.domain.revision.RevisionChangeType;
import com.universe.wiki.domain.revision.WikiArticleRevision;
import com.universe.wiki.infrastructure.markdown.CommonMarkWikiMarkdownImageExtractor;
import com.universe.wiki.infrastructure.persistence.image.SpringDataWikiArticleImageReferenceJpaRepository;
import com.universe.wiki.infrastructure.persistence.image.SpringDataWikiImageJpaRepository;
import com.universe.wiki.infrastructure.persistence.image.SpringDataWikiRevisionImageReferenceJpaRepository;
import com.universe.wiki.infrastructure.persistence.image.WikiArticleImageReferenceJpaEntity;
import com.universe.wiki.infrastructure.persistence.image.WikiImageJpaEntity;
import com.universe.wiki.infrastructure.persistence.image.WikiImageReferenceSynchronizer;
import com.universe.wiki.infrastructure.persistence.image.WikiRevisionImageReferenceJpaEntity;

import com.universe.wiki.infrastructure.persistence.revision.WikiArticleRevisionPersistenceAdapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ CommonMarkWikiMarkdownImageExtractor.class, WikiImageReferenceSynchronizer.class,
		WikiArticlePersistenceAdapter.class, WikiArticleRevisionPersistenceAdapter.class })
@TestPropertySource(properties = { "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true" })
class WikiArticleMediaReferenceIntegrationTest {

	private static final String TEST_USER_ID = "88888888-8888-8888-8888-888888888888";

	@Autowired
	private WikiArticlePersistenceAdapter articlePersistenceAdapter;

	@Autowired
	private WikiArticleRevisionPersistenceAdapter revisionPersistenceAdapter;

	@Autowired
	private SpringDataWikiImageJpaRepository imageRepository;

	@Autowired
	private SpringDataWikiArticleImageReferenceJpaRepository articleRefRepository;

	@Autowired
	private SpringDataWikiRevisionImageReferenceJpaRepository revisionRefRepository;

	@DynamicPropertySource
	static void configureDatabaseProperties(DynamicPropertyRegistry registry) {
		TestDatabaseSupport.configureDynamicProperties(registry);
	}

	@Test
	@DisplayName("End-to-End: Thay thế ảnh Media A bằng Media B cập nhật article references và giữ nguyên revision references của Revision 1")
	void shouldTrackMediaReferencesAndPreserveRevisionImmutability() {
		// 1. Seed Media Image A, Media Image B, and Legacy Image
		UUID mediaAssetAId = UUID.randomUUID();
		String mediaUrlA = "/media/assets/" + mediaAssetAId + "/content";
		String imageAId = insertWikiImage("test_hash_a", null, mediaAssetAId.toString(), mediaUrlA);

		UUID mediaAssetBId = UUID.randomUUID();
		String mediaUrlB = "/media/assets/" + mediaAssetBId + "/content";
		String imageBId = insertWikiImage("test_hash_b", null, mediaAssetBId.toString(), mediaUrlB);

		String legacyUrl = "https://res.cloudinary.com/demo/image/upload/v1/kiemlai/wiki/legacy.webp";
		String legacyImageId = insertWikiImage("test_hash_legacy", "legacy_public_id", null, legacyUrl);

		// 2. Save Article version 1 with Image A + Legacy Image
		UUID articleId = UUID.randomUUID();
		String markdownV1 = """
				# Trần Bình An

				![Chân dung A](""" + mediaUrlA + """
				 "wiki:width=50;layout=block-center")

				![Ảnh cũ](""" + legacyUrl + """
				)
				""";

		WikiArticle article = WikiArticle.createPublished(articleId, "Trần Bình An", new Slug("tran-binh-an"),
				ArticleType.CHARACTER, "Tóm tắt Trần Bình An", markdownV1, UUID.fromString(TEST_USER_ID),
				Instant.now());

		articlePersistenceAdapter.save(article);

		// Verify article references contain Image A and Legacy Image
		List<WikiArticleImageReferenceJpaEntity> articleRefsV1 = articleRefRepository
				.findByArticleId(articleId.toString());
		assertThat(articleRefsV1).hasSize(2);
		assertThat(articleRefsV1).extracting(WikiArticleImageReferenceJpaEntity::getImageId)
				.containsExactlyInAnyOrder(imageAId, legacyImageId);

		// 3. Save Revision 1 for version 1
		UUID revision1Id = UUID.randomUUID();
		WikiArticleRevision revision1 = new WikiArticleRevision(revision1Id, articleId, 1L, 1L, "Trần Bình An",
				new Slug("tran-binh-an"), ArticleType.CHARACTER, "Tóm tắt Trần Bình An", markdownV1,
				ArticleStatus.PUBLISHED, RevisionChangeType.CREATE_AND_PUBLISH, "Khởi tạo bài viết",
				UUID.fromString(TEST_USER_ID), Instant.now());

		revisionPersistenceAdapter.save(revision1);

		// Verify revision 1 references contain Image A and Legacy Image
		List<WikiRevisionImageReferenceJpaEntity> rev1Refs = revisionRefRepository
				.findByRevisionId(revision1Id.toString());
		assertThat(rev1Refs).hasSize(2);
		assertThat(rev1Refs).extracting(WikiRevisionImageReferenceJpaEntity::getImageId)
				.containsExactlyInAnyOrder(imageAId, legacyImageId);

		// 4. Update Article to version 2 replacing Image A with Image B (now Image B +
		// Legacy Image)
		String markdownV2 = """
				# Trần Bình An (Cập nhật)

				![Chân dung B](""" + mediaUrlB + """
				 "wiki:width=60;layout=block-center")

				![Ảnh cũ](""" + legacyUrl + """
				)
				""";

		article.updatePublishedContent("Tóm tắt Trần Bình An", markdownV2, UUID.fromString(TEST_USER_ID),
				Instant.now());

		articlePersistenceAdapter.save(article);

		// Verify article references now contain Image B and Legacy Image, and NO longer
		// contain Image A
		List<WikiArticleImageReferenceJpaEntity> articleRefsV2 = articleRefRepository
				.findByArticleId(articleId.toString());
		assertThat(articleRefsV2).hasSize(2);
		assertThat(articleRefsV2).extracting(WikiArticleImageReferenceJpaEntity::getImageId)
				.containsExactlyInAnyOrder(imageBId, legacyImageId).doesNotContain(imageAId);

		// 5. Save Revision 2 for version 2
		UUID revision2Id = UUID.randomUUID();
		WikiArticleRevision revision2 = new WikiArticleRevision(revision2Id, articleId, 2L, 2L, "Trần Bình An",
				new Slug("tran-binh-an"), ArticleType.CHARACTER, "Tóm tắt Trần Bình An", markdownV2,
				ArticleStatus.PUBLISHED, RevisionChangeType.UPDATE_PUBLISHED, "Thay ảnh chân dung mới",
				UUID.fromString(TEST_USER_ID), Instant.now());

		revisionPersistenceAdapter.save(revision2);

		// Verify revision 2 references contain Image B and Legacy Image
		List<WikiRevisionImageReferenceJpaEntity> rev2Refs = revisionRefRepository
				.findByRevisionId(revision2Id.toString());
		assertThat(rev2Refs).hasSize(2);
		assertThat(rev2Refs).extracting(WikiRevisionImageReferenceJpaEntity::getImageId)
				.containsExactlyInAnyOrder(imageBId, legacyImageId);

		// 6. CRITICAL VERIFICATION: Revision 1 references are completely intact and
		// STILL reference Image A + Legacy
		List<WikiRevisionImageReferenceJpaEntity> rev1RefsAfterV2 = revisionRefRepository
				.findByRevisionId(revision1Id.toString());
		assertThat(rev1RefsAfterV2).hasSize(2);
		assertThat(rev1RefsAfterV2).extracting(WikiRevisionImageReferenceJpaEntity::getImageId)
				.containsExactlyInAnyOrder(imageAId, legacyImageId).doesNotContain(imageBId);
	}

	private String insertWikiImage(String contentHash, String publicId, String mediaAssetId, String url) {
		String imageId = UUID.randomUUID().toString();
		WikiImageJpaEntity entity = new WikiImageJpaEntity();
		entity.setId(imageId);
		entity.setContentHash(contentHash);
		entity.setPublicId(publicId);
		entity.setMediaAssetId(mediaAssetId);
		entity.setUrl(url);
		entity.setSourceContentType("image/webp");
		entity.setSizeBytes(2048L);
		entity.setCreatedAt(Instant.now());
		imageRepository.save(entity);
		return imageId;
	}
}
