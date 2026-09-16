package com.universe.wiki.application.saved;

import com.universe.wiki.application.ports.WikiSavedArticleRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UnsaveWikiArticleUseCase Unit Tests")
class UnsaveWikiArticleUseCaseTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ARTICLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private WikiSavedArticleRepositoryPort wikiSavedArticleRepositoryPort;

    private UnsaveWikiArticleUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new UnsaveWikiArticleUseCase(wikiSavedArticleRepositoryPort);
    }

    @Test
    @DisplayName("Xóa bản ghi lưu khi bài viết tồn tại trong danh sách lưu")
    void shouldDeleteExistingRecord() {
        when(wikiSavedArticleRepositoryPort.deleteByUserIdAndArticleId(USER_ID, ARTICLE_ID)).thenReturn(true);

        UnsaveWikiArticleCommand command = new UnsaveWikiArticleCommand(USER_ID, ARTICLE_ID);
        useCase.execute(command);

        verify(wikiSavedArticleRepositoryPort).deleteByUserIdAndArticleId(USER_ID, ARTICLE_ID);
    }

    @Test
    @DisplayName("Idempotent success: thành công ngay cả khi bản ghi lưu không tồn tại")
    void shouldBeIdempotentWhenRecordDoesNotExist() {
        when(wikiSavedArticleRepositoryPort.deleteByUserIdAndArticleId(USER_ID, ARTICLE_ID)).thenReturn(false);

        UnsaveWikiArticleCommand command = new UnsaveWikiArticleCommand(USER_ID, ARTICLE_ID);
        useCase.execute(command);

        verify(wikiSavedArticleRepositoryPort).deleteByUserIdAndArticleId(USER_ID, ARTICLE_ID);
    }

    @Test
    @DisplayName("Từ chối lệnh null")
    void shouldRejectNullCommand() {
        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("UnsaveWikiArticleCommand không được để trống.");
    }
}
