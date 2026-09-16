package com.universe.wiki.application.saved;

import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.exceptions.DuplicateWikiSavedArticleException;
import com.universe.wiki.application.exceptions.PublishedWikiArticleNotFoundException;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.application.ports.WikiSavedArticleRepositoryPort;
import com.universe.wiki.domain.saved.UserSavedWikiArticle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SaveWikiArticleUseCase Unit Tests")
class SaveWikiArticleUseCaseTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ARTICLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID GENERATED_SAVED_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant FROZEN_NOW = Instant.parse("2026-09-16T12:00:00Z");

    @Mock
    private WikiArticleQueryPort wikiArticleQueryPort;

    @Mock
    private WikiSavedArticleRepositoryPort wikiSavedArticleRepositoryPort;

    @Mock
    private IdGeneratorPort idGeneratorPort;

    @Mock
    private ClockPort clockPort;

    private SaveWikiArticleUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new SaveWikiArticleUseCase(
                wikiArticleQueryPort,
                wikiSavedArticleRepositoryPort,
                idGeneratorPort,
                clockPort
        );
    }

    @Test
    @DisplayName("A. Đã lưu từ trước + bài viết sau đó bị gỡ (DRAFT/ARCHIVED): thành công idempotent, không bao giờ gọi isPublished hay save")
    void shouldSucceedIdempotentlyWithoutCheckingPublicationWhenAlreadySaved() {
        // Bản ghi lưu đã tồn tại trong database
        when(wikiSavedArticleRepositoryPort.existsByUserIdAndArticleId(USER_ID, ARTICLE_ID)).thenReturn(true);

        SaveWikiArticleCommand command = new SaveWikiArticleCommand(USER_ID, ARTICLE_ID);
        useCase.execute(command);

        // Xác thực quy trình:
        // 1. Kiểm tra tồn tại trả về true
        verify(wikiSavedArticleRepositoryPort).existsByUserIdAndArticleId(USER_ID, ARTICLE_ID);
        // 2. isPublished tuyệt đối không được gọi
        verify(wikiArticleQueryPort, never()).isPublished(any());
        // 3. Không sinh ID, không lấy thời gian, không lưu lại vào DB
        verify(idGeneratorPort, never()).generate();
        verify(clockPort, never()).now();
        verify(wikiSavedArticleRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("B. Chưa lưu + bài viết chưa xuất bản (DRAFT/ARCHIVED/không tồn tại): từ chối và ném PublishedWikiArticleNotFoundException")
    void shouldThrowExceptionWhenNotSavedAndArticleIsNotPublished() {
        when(wikiSavedArticleRepositoryPort.existsByUserIdAndArticleId(USER_ID, ARTICLE_ID)).thenReturn(false);
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(false);

        SaveWikiArticleCommand command = new SaveWikiArticleCommand(USER_ID, ARTICLE_ID);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(PublishedWikiArticleNotFoundException.class)
                .hasMessageContaining(ARTICLE_ID.toString());

        verify(wikiSavedArticleRepositoryPort).existsByUserIdAndArticleId(USER_ID, ARTICLE_ID);
        verify(wikiArticleQueryPort).isPublished(ARTICLE_ID);
        verify(idGeneratorPort, never()).generate();
        verify(clockPort, never()).now();
        verify(wikiSavedArticleRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("C. Chưa lưu + bài viết đang PUBLISHED: lưu thành công bình thường với ClockPort và IdGeneratorPort")
    void shouldSavePublishedArticleWhenNotAlreadySaved() {
        when(wikiSavedArticleRepositoryPort.existsByUserIdAndArticleId(USER_ID, ARTICLE_ID)).thenReturn(false);
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(true);
        when(idGeneratorPort.generate()).thenReturn(GENERATED_SAVED_ID);
        when(clockPort.now()).thenReturn(FROZEN_NOW);

        SaveWikiArticleCommand command = new SaveWikiArticleCommand(USER_ID, ARTICLE_ID);
        useCase.execute(command);

        verify(wikiSavedArticleRepositoryPort).existsByUserIdAndArticleId(USER_ID, ARTICLE_ID);
        verify(wikiArticleQueryPort).isPublished(ARTICLE_ID);

        ArgumentCaptor<UserSavedWikiArticle> captor = ArgumentCaptor.forClass(UserSavedWikiArticle.class);
        verify(wikiSavedArticleRepositoryPort).save(captor.capture());

        UserSavedWikiArticle saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(GENERATED_SAVED_ID);
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getArticleId()).isEqualTo(ARTICLE_ID);
        assertThat(saved.getCreatedAt()).isEqualTo(FROZEN_NOW);
    }

    @Test
    @DisplayName("D. Tranh chấp ghi đồng thời (concurrent duplicate): bắt DuplicateWikiSavedArticleException và hội tụ thành công idempotent")
    void shouldHandleConcurrentDuplicateSaveIdempotently() {
        when(wikiSavedArticleRepositoryPort.existsByUserIdAndArticleId(USER_ID, ARTICLE_ID)).thenReturn(false);
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(true);
        when(idGeneratorPort.generate()).thenReturn(GENERATED_SAVED_ID);
        when(clockPort.now()).thenReturn(FROZEN_NOW);

        doThrow(new DuplicateWikiSavedArticleException(USER_ID, ARTICLE_ID, new RuntimeException("duplicate key")))
                .when(wikiSavedArticleRepositoryPort).save(any());

        SaveWikiArticleCommand command = new SaveWikiArticleCommand(USER_ID, ARTICLE_ID);

        // Phải thành công mà không ném lỗi ra ngoài
        useCase.execute(command);

        verify(wikiSavedArticleRepositoryPort).save(any());
    }

    @Test
    @DisplayName("Từ chối lệnh null")
    void shouldRejectNullCommand() {
        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("SaveWikiArticleCommand không được để trống.");
    }
}
