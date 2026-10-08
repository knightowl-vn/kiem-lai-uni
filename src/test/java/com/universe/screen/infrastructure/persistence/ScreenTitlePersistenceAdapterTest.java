package com.universe.screen.infrastructure.persistence;

import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.domain.ScreenTitle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScreenTitlePersistenceAdapterTest {

    @Mock
    private SpringDataScreenTitleJpaRepository repository;

    private ScreenTitlePersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ScreenTitlePersistenceAdapter(repository);
    }

    @Test
    @DisplayName("PersistenceAdapter: findAll returns titles sorted and mapped to domain")
    void shouldFindAllTitles() {
        UUID id = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                id, "title-slug", "Title Display", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, Instant.parse("2026-10-06T10:00:00Z")
        );
        ScreenTitleJpaEntity entity = ScreenTitlePersistenceMapper.toEntity(title);

        when(repository.findAll(any(Sort.class))).thenReturn(List.of(entity));

        List<ScreenTitle> titles = adapter.findAll();

        assertThat(titles).hasSize(1);
        assertThat(titles.get(0).getId()).isEqualTo(id);
        assertThat(titles.get(0).getTitle()).isEqualTo("Title Display");
    }
}
