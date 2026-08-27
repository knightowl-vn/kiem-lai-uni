package com.universe.wiki.infrastructure.persistence.article;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WikiArticleAliasJpaEntityTest {

    @Test
    @DisplayName("Khởi tạo và truy xuất các trường của WikiArticleAliasJpaEntity")
    void testEntityProperties() {
        String id = UUID.randomUUID().toString();
        String articleId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        WikiArticleAliasJpaEntity entity = new WikiArticleAliasJpaEntity(
                id,
                articleId,
                "Tiểu Phu Tử",
                "tiểu phu tử",
                now
        );

        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getArticleId()).isEqualTo(articleId);
        assertThat(entity.getAlias()).isEqualTo("Tiểu Phu Tử");
        assertThat(entity.getNormalizedAlias()).isEqualTo("tiểu phu tử");
        assertThat(entity.getCreatedAt()).isEqualTo(now);

        entity.setAlias("Trần Tiên Sinh");
        entity.setNormalizedAlias("trần tiên sinh");
        assertThat(entity.getAlias()).isEqualTo("Trần Tiên Sinh");
        assertThat(entity.getNormalizedAlias()).isEqualTo("trần tiên sinh");

        WikiArticleAliasJpaEntity sameIdEntity = new WikiArticleAliasJpaEntity();
        sameIdEntity.setId(id);
        assertThat(entity).isEqualTo(sameIdEntity);
        assertThat(entity.hashCode()).isEqualTo(sameIdEntity.hashCode());
    }
}