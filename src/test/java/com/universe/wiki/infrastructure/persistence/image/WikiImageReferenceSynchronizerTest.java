package com.universe.wiki.infrastructure.persistence.image;

import com.universe.wiki.application.ports.WikiMarkdownImageExtractor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WikiImageReferenceSynchronizerTest {

    private static final UUID ARTICLE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID REVISION_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final String IMAGE_A_ID = "11111111-1111-1111-1111-111111111111";
    private static final String IMAGE_A_URL = "/media/assets/11111111-1111-1111-1111-111111111111/content";

    private static final String IMAGE_B_ID = "22222222-2222-2222-2222-222222222222";
    private static final String IMAGE_B_URL = "/media/assets/22222222-2222-2222-2222-222222222222/content";

    private static final String LEGACY_IMAGE_ID = "33333333-3333-3333-3333-333333333333";
    private static final String LEGACY_IMAGE_URL = "https://res.cloudinary.com/demo/image/upload/v1/kiemlai/wiki/legacy.webp";

    @Mock
    private WikiMarkdownImageExtractor imageExtractor;

    @Mock
    private SpringDataWikiImageJpaRepository imageRepository;

    @Mock
    private SpringDataWikiArticleImageReferenceJpaRepository articleReferenceRepository;

    @Mock
    private SpringDataWikiRevisionImageReferenceJpaRepository revisionReferenceRepository;

    private WikiImageReferenceSynchronizer synchronizer;

    @BeforeEach
    void setUp() {
        synchronizer = new WikiImageReferenceSynchronizer(
                imageExtractor,
                imageRepository,
                articleReferenceRepository,
                revisionReferenceRepository
        );
    }

    @Test
    @DisplayName("syncArticleReferences tạo reference mới cho ảnh Media-backed khi lưu bài viết")
    void shouldCreateArticleReferenceForMediaBackedImage() {
        String markdown = "![Ảnh A](" + IMAGE_A_URL + ")";

        WikiImageJpaEntity imageAEntity = new WikiImageJpaEntity();
        imageAEntity.setId(IMAGE_A_ID);
        imageAEntity.setUrl(IMAGE_A_URL);

        when(imageExtractor.extractImageUrls(markdown)).thenReturn(Set.of(IMAGE_A_URL));
        when(imageRepository.findByUrlIn(Set.of(IMAGE_A_URL))).thenReturn(List.of(imageAEntity));
        when(articleReferenceRepository.findByArticleId(ARTICLE_ID.toString())).thenReturn(List.of());

        synchronizer.syncArticleReferences(ARTICLE_ID, markdown);

        ArgumentCaptor<WikiArticleImageReferenceJpaEntity> captor =
                ArgumentCaptor.forClass(WikiArticleImageReferenceJpaEntity.class);
        verify(articleReferenceRepository).save(captor.capture());

        WikiArticleImageReferenceJpaEntity savedRef = captor.getValue();
        assertThat(savedRef.getArticleId()).isEqualTo(ARTICLE_ID.toString());
        assertThat(savedRef.getImageId()).isEqualTo(IMAGE_A_ID);
        assertThat(savedRef.getId()).isNotNull();
    }

    @Test
    @DisplayName("syncArticleReferences thay thế ảnh A bằng ảnh B: xóa reference A và thêm reference B")
    void shouldReplaceImageAReferenceWithImageBReferenceForArticle() {
        String markdownWithB = "![Ảnh B](" + IMAGE_B_URL + ")";

        WikiImageJpaEntity imageBEntity = new WikiImageJpaEntity();
        imageBEntity.setId(IMAGE_B_ID);
        imageBEntity.setUrl(IMAGE_B_URL);

        WikiArticleImageReferenceJpaEntity existingRefA = new WikiArticleImageReferenceJpaEntity();
        existingRefA.setId(UUID.randomUUID().toString());
        existingRefA.setArticleId(ARTICLE_ID.toString());
        existingRefA.setImageId(IMAGE_A_ID);

        when(imageExtractor.extractImageUrls(markdownWithB)).thenReturn(Set.of(IMAGE_B_URL));
        when(imageRepository.findByUrlIn(Set.of(IMAGE_B_URL))).thenReturn(List.of(imageBEntity));
        when(articleReferenceRepository.findByArticleId(ARTICLE_ID.toString()))
                .thenReturn(List.of(existingRefA));

        synchronizer.syncArticleReferences(ARTICLE_ID, markdownWithB);

        // Verify delete of old reference A
        verify(articleReferenceRepository).deleteAll(List.of(existingRefA));

        // Verify save of new reference B
        ArgumentCaptor<WikiArticleImageReferenceJpaEntity> captor =
                ArgumentCaptor.forClass(WikiArticleImageReferenceJpaEntity.class);
        verify(articleReferenceRepository).save(captor.capture());
        assertThat(captor.getValue().getImageId()).isEqualTo(IMAGE_B_ID);
    }

    @Test
    @DisplayName("syncRevisionReferences lưu reference bất biến cho revision với ảnh Media-backed")
    void shouldCreateImmutableRevisionReferenceForMediaBackedImage() {
        String markdown = "![Ảnh A](" + IMAGE_A_URL + ")";

        WikiImageJpaEntity imageAEntity = new WikiImageJpaEntity();
        imageAEntity.setId(IMAGE_A_ID);
        imageAEntity.setUrl(IMAGE_A_URL);

        when(imageExtractor.extractImageUrls(markdown)).thenReturn(Set.of(IMAGE_A_URL));
        when(imageRepository.findByUrlIn(Set.of(IMAGE_A_URL))).thenReturn(List.of(imageAEntity));
        when(revisionReferenceRepository.findByRevisionId(REVISION_ID.toString())).thenReturn(List.of());

        synchronizer.syncRevisionReferences(REVISION_ID, markdown);

        ArgumentCaptor<WikiRevisionImageReferenceJpaEntity> captor =
                ArgumentCaptor.forClass(WikiRevisionImageReferenceJpaEntity.class);
        verify(revisionReferenceRepository).save(captor.capture());

        WikiRevisionImageReferenceJpaEntity savedRef = captor.getValue();
        assertThat(savedRef.getRevisionId()).isEqualTo(REVISION_ID.toString());
        assertThat(savedRef.getImageId()).isEqualTo(IMAGE_A_ID);
    }

    @Test
    @DisplayName("syncArticleReferences đồng thời hỗ trợ cả URL Media-backed và URL legacy Cloudinary")
    void shouldSynchronizeBothMediaAndLegacyReferences() {
        String markdown = "![Media](" + IMAGE_A_URL + ") ![Legacy](" + LEGACY_IMAGE_URL + ")";

        WikiImageJpaEntity imageAEntity = new WikiImageJpaEntity();
        imageAEntity.setId(IMAGE_A_ID);
        imageAEntity.setUrl(IMAGE_A_URL);

        WikiImageJpaEntity legacyEntity = new WikiImageJpaEntity();
        legacyEntity.setId(LEGACY_IMAGE_ID);
        legacyEntity.setUrl(LEGACY_IMAGE_URL);

        when(imageExtractor.extractImageUrls(markdown)).thenReturn(Set.of(IMAGE_A_URL, LEGACY_IMAGE_URL));
        when(imageRepository.findByUrlIn(Set.of(IMAGE_A_URL, LEGACY_IMAGE_URL)))
                .thenReturn(List.of(imageAEntity, legacyEntity));
        when(articleReferenceRepository.findByArticleId(ARTICLE_ID.toString())).thenReturn(List.of());

        synchronizer.syncArticleReferences(ARTICLE_ID, markdown);

        ArgumentCaptor<WikiArticleImageReferenceJpaEntity> captor =
                ArgumentCaptor.forClass(WikiArticleImageReferenceJpaEntity.class);
        verify(articleReferenceRepository, times(2)).save(captor.capture());

        List<String> savedImageIds = captor.getAllValues().stream()
                .map(WikiArticleImageReferenceJpaEntity::getImageId)
                .toList();
        assertThat(savedImageIds).containsExactlyInAnyOrder(IMAGE_A_ID, LEGACY_IMAGE_ID);
    }

    @Test
    @DisplayName("syncArticleReferences không làm gì khi articleId là null")
    void shouldDoNothingWhenArticleIdIsNull() {
        synchronizer.syncArticleReferences(null, "some markdown");
        verify(imageExtractor, never()).extractImageUrls(any());
        verify(articleReferenceRepository, never()).findByArticleId(any());
    }

    @Test
    @DisplayName("syncRevisionReferences không làm gì khi revisionId là null")
    void shouldDoNothingWhenRevisionIdIsNull() {
        synchronizer.syncRevisionReferences(null, "some markdown");
        verify(imageExtractor, never()).extractImageUrls(any());
        verify(revisionReferenceRepository, never()).findByRevisionId(any());
    }
}
