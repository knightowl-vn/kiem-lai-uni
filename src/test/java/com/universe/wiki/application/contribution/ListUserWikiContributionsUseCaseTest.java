package com.universe.wiki.application.contribution;

import com.universe.wiki.application.ports.UserWikiContributionsQueryPort;
import com.universe.wiki.contracts.dto.contribution.UserWikiContributionItemDTO;
import com.universe.wiki.contracts.dto.contribution.UserWikiContributionPageDTO;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ListUserWikiContributionsUseCase Unit Tests")
class ListUserWikiContributionsUseCaseTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private UserWikiContributionsQueryPort queryPort;

    private ListUserWikiContributionsUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ListUserWikiContributionsUseCase(queryPort);
    }

    @Test
    @DisplayName("Thành công: truy vấn phân trang với page và size hợp lệ")
    void shouldReturnPageWhenValidParameters() {
        UserWikiContributionItemDTO item = new UserWikiContributionItemDTO(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Trần Bình An",
                "tran-binh-an",
                "CHARACTER",
                "FULL_ARTICLE",
                "INCORRECT_INFORMATION",
                "Cần đính chính",
                "RESOLVED",
                Instant.now(),
                "Đã tiếp thu và cập nhật",
                Instant.now().plusSeconds(3600)
        );

        UserWikiContributionPageDTO expectedPage = new UserWikiContributionPageDTO(
                List.of(item),
                0,
                20,
                1L,
                1,
                true,
                true
        );
        when(queryPort.findByUserId(USER_ID, 0, 20)).thenReturn(expectedPage);

        UserWikiContributionPageDTO result = useCase.execute(USER_ID, 0, 20);

        assertThat(result).isSameAs(expectedPage);
        verify(queryPort).findByUserId(USER_ID, 0, 20);
    }

    @Test
    @DisplayName("Thành công: sử dụng phương thức rút gọn (overload) với DEFAULT_PAGE_SIZE = 20")
    void shouldDelegateWithDefaultPageSizeWhenUsingOverload() {
        UserWikiContributionPageDTO expectedPage = new UserWikiContributionPageDTO(
                List.of(),
                1,
                20,
                25L,
                2,
                false,
                true
        );
        when(queryPort.findByUserId(USER_ID, 1, ListUserWikiContributionsUseCase.DEFAULT_PAGE_SIZE))
                .thenReturn(expectedPage);

        UserWikiContributionPageDTO result = useCase.execute(USER_ID, 1);

        assertThat(result).isSameAs(expectedPage);
        verify(queryPort).findByUserId(USER_ID, 1, 20);
    }

    @Test
    @DisplayName("Thất bại: ném NullPointerException khi userId là null")
    void shouldThrowWhenUserIdIsNull() {
        assertThatThrownBy(() -> useCase.execute(null, 0, 20))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("userId");
        verifyNoInteractions(queryPort);
    }

    @Test
    @DisplayName("Thất bại: ném IllegalArgumentException khi page < 0")
    void shouldThrowWhenPageIsNegative() {
        assertThatThrownBy(() -> useCase.execute(USER_ID, -1, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page không được nhỏ hơn 0");
        verifyNoInteractions(queryPort);
    }

    @Test
    @DisplayName("Thất bại: ném IllegalArgumentException khi size < 1")
    void shouldThrowWhenSizeIsLessThanOne() {
        assertThatThrownBy(() -> useCase.execute(USER_ID, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page size phải lớn hơn hoặc bằng 1");
        verifyNoInteractions(queryPort);
    }

    @Test
    @DisplayName("Thất bại: ném IllegalArgumentException khi size > 100")
    void shouldThrowWhenSizeExceedsMaximum() {
        assertThatThrownBy(() -> useCase.execute(USER_ID, 0, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("không được vượt quá 100");
        verifyNoInteractions(queryPort);
    }

    @Test
    @DisplayName("Thất bại: ném NullPointerException khi queryPort null trong constructor")
    void shouldThrowWhenPortIsNullInConstructor() {
        assertThatThrownBy(() -> new ListUserWikiContributionsUseCase(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("UserWikiContributionsQueryPort");
    }
}
