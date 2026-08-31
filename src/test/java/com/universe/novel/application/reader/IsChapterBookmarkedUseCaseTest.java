package com.universe.novel.application.reader;

import com.universe.novel.application.ports.ReaderBookmarkedChaptersQueryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IsChapterBookmarkedUseCaseTest {

    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID CHAPTER_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private ReaderBookmarkedChaptersQueryPort queryPort;

    private IsChapterBookmarkedUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new IsChapterBookmarkedUseCase(queryPort);
    }

    @Test
    @DisplayName("Ủy quyền kiểm tra trạng thái bookmark cho ReaderBookmarkedChaptersQueryPort")
    void shouldDelegateToIsBookmarked() {
        when(queryPort.isBookmarked(USER_ID, CHAPTER_ID)).thenReturn(true);

        boolean result = useCase.execute(USER_ID, CHAPTER_ID);

        assertThat(result).isTrue();
        verify(queryPort).isBookmarked(USER_ID, CHAPTER_ID);
    }

    @Test
    @DisplayName("Từ chối kiểm tra khi userId là null")
    void shouldRejectNullUserId() {
        assertThatThrownBy(() -> useCase.execute(null, CHAPTER_ID))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("ID người dùng không được để trống.");
    }

    @Test
    @DisplayName("Từ chối kiểm tra khi chapterId là null")
    void shouldRejectNullChapterId() {
        assertThatThrownBy(() -> useCase.execute(USER_ID, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("ID chương không được để trống.");
    }
}
