package com.universe.wiki.infrastructure.persistence.article;

import com.universe.wiki.contracts.dto.WikiArticleAliasDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WikiArticleAliasPersistenceAdapterTest {

    @Mock
    private SpringDataWikiArticleAliasJpaRepository jpaRepository;

    private WikiArticleAliasPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new WikiArticleAliasPersistenceAdapter(jpaRepository);
    }

    @Test
    @DisplayName("listByArticleId chuyển đổi danh sách JPA Entity thành DTO")
    void testListByArticleId() {
        UUID articleId = UUID.randomUUID();
        UUID aliasId = UUID.randomUUID();
        Instant now = Instant.now();

        WikiArticleAliasJpaEntity entity = new WikiArticleAliasJpaEntity(
                aliasId.toString(), articleId.toString(), "Tiểu Phu Tử", "tiểu phu tử", now
        );
        when(jpaRepository.findAllByArticleIdOrderByCreatedAtAsc(articleId.toString()))
                .thenReturn(List.of(entity));

        List<WikiArticleAliasDTO> result = adapter.listByArticleId(articleId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(aliasId);
        assertThat(result.get(0).articleId()).isEqualTo(articleId);
        assertThat(result.get(0).alias()).isEqualTo("Tiểu Phu Tử");
        assertThat(result.get(0).normalizedAlias()).isEqualTo("tiểu phu tử");
        assertThat(result.get(0).createdAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("findByArticleIdAndNormalizedAlias tìm và map thành DTO")
    void testFindByArticleIdAndNormalizedAlias() {
        UUID articleId = UUID.randomUUID();
        UUID aliasId = UUID.randomUUID();
        Instant now = Instant.now();

        WikiArticleAliasJpaEntity entity = new WikiArticleAliasJpaEntity(
                aliasId.toString(), articleId.toString(), "Tiểu Phu Tử", "tiểu phu tử", now
        );
        when(jpaRepository.findByArticleIdAndNormalizedAlias(articleId.toString(), "tiểu phu tử"))
                .thenReturn(Optional.of(entity));

        Optional<WikiArticleAliasDTO> result = adapter.findByArticleIdAndNormalizedAlias(articleId, "tiểu phu tử");

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(aliasId);
    }

    @Test
    @DisplayName("save lưu JPA entity với đúng các trường")
    void testSave() {
        UUID id = UUID.randomUUID();
        UUID articleId = UUID.randomUUID();
        Instant now = Instant.now();

        adapter.save(id, articleId, "Tiểu Phu Tử", "tiểu phu tử", now);

        ArgumentCaptor<WikiArticleAliasJpaEntity> captor = ArgumentCaptor.forClass(WikiArticleAliasJpaEntity.class);
        verify(jpaRepository).save(captor.capture());

        WikiArticleAliasJpaEntity saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(id.toString());
        assertThat(saved.getArticleId()).isEqualTo(articleId.toString());
        assertThat(saved.getAlias()).isEqualTo("Tiểu Phu Tử");
        assertThat(saved.getNormalizedAlias()).isEqualTo("tiểu phu tử");
        assertThat(saved.getCreatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("deleteByArticleIdAndNormalizedAlias gọi đúng phương thức của JPA Repository")
    void testDelete() {
        UUID articleId = UUID.randomUUID();
        adapter.deleteByArticleIdAndNormalizedAlias(articleId, "tiểu phu tử");

        verify(jpaRepository).deleteByArticleIdAndNormalizedAlias(articleId.toString(), "tiểu phu tử");
    }

    @Test
    @DisplayName("existsByArticleIdAndNormalizedAlias gọi đúng phương thức của JPA Repository")
    void testExists() {
        UUID articleId = UUID.randomUUID();
        when(jpaRepository.existsByArticleIdAndNormalizedAlias(articleId.toString(), "tiểu phu tử"))
                .thenReturn(true);

        boolean exists = adapter.existsByArticleIdAndNormalizedAlias(articleId, "tiểu phu tử");

        assertThat(exists).isTrue();
    }
}