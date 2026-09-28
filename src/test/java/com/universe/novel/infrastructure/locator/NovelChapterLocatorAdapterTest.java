package com.universe.novel.infrastructure.locator;

import com.universe.novel.application.locator.LocatePublishedChaptersUseCase;
import com.universe.novel.contracts.dto.locator.NovelChapterLocatorItemDTO;
import com.universe.novel.contracts.dto.locator.NovelChapterLocatorResultDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NovelChapterLocatorAdapterTest {

    @Mock
    private LocatePublishedChaptersUseCase useCase;

    private NovelChapterLocatorAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new NovelChapterLocatorAdapter(useCase);
    }

    @Test
    @DisplayName("Delegates locateChapters with explicit limit to use case")
    void shouldDelegateWithExplicitLimit() {
        NovelChapterLocatorResultDTO expected = new NovelChapterLocatorResultDTO(
                "123",
                123,
                List.of(new NovelChapterLocatorItemDTO(
                        UUID.randomUUID(), 123, "Khởi Đầu", "quyen-1-chuong-123", 1, "Quyển 1"
                ))
        );
        when(useCase.locate("123", 10)).thenReturn(expected);

        NovelChapterLocatorResultDTO actual = adapter.locateChapters("123", 10);

        assertThat(actual).isSameAs(expected);
        verify(useCase).locate("123", 10);
    }

    @Test
    @DisplayName("Default method invokes locateChapters with default limit 20")
    void shouldDelegateWithDefaultLimit() {
        NovelChapterLocatorResultDTO expected = new NovelChapterLocatorResultDTO(
                "chuong 1",
                1,
                List.of()
        );
        when(useCase.locate("chuong 1", 20)).thenReturn(expected);

        NovelChapterLocatorResultDTO actual = adapter.locateChapters("chuong 1");

        assertThat(actual).isSameAs(expected);
        verify(useCase).locate("chuong 1", 20);
    }
}
