package com.universe.novel.application.reader;

import com.universe.novel.application.ports.ReaderBookmarkedChaptersQueryPort;
import com.universe.novel.contracts.dto.reader.ReaderBookmarkedChapterDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListUserBookmarkedChaptersUseCaseTest {

    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID CHAPTER_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private ReaderBookmarkedChaptersQueryPort queryPort;

    private ListUserBookmarkedChaptersUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ListUserBookmarkedChaptersUseCase(queryPort);
    }

    @Test
    @DisplayName("Ủy quyền truy vấn danh sách bookmark cho ReaderBookmarkedChaptersQueryPort")
    void shouldDelegateToListUserBookmarkedChapters() {
        List<ReaderBookmarkedChapterDTO> expected = List.of(
                new ReaderBookmarkedChapterDTO(
                        CHAPTER_ID,
                        1,
                        "Chương 1",
                        "chuong-1",
                        "Quyển 1",
                        Instant.now()
                )
        );

        when(queryPort.findBookmarkedChaptersByUserId(USER_ID)).thenReturn(expected);

        List<ReaderBookmarkedChapterDTO> actual = useCase.execute(USER_ID);

        assertThat(actual).isSameAs(expected);
        verify(queryPort).findBookmarkedChaptersByUserId(USER_ID);
    }

    @Test
    @DisplayName("Từ chối truy vấn khi userId là null")
    void shouldRejectNullUserId() {
        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("ID người dùng không được để trống.");
    }
}
