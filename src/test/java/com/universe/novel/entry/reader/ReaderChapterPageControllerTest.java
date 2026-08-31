package com.universe.novel.entry.reader;

import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.reader.GetReaderChapterDetailUseCase;
import com.universe.novel.application.reader.IsChapterBookmarkedUseCase;
import com.universe.novel.contracts.dto.reader.ReaderChapterDetailDTO;
import com.universe.novel.contracts.dto.reader.ReaderChapterNavigationDTO;
import com.universe.novel.contracts.dto.reader.ReaderChapterTocItemDTO;
import com.universe.novel.contracts.dto.reader.ReaderVolumeSummaryDTO;
import com.universe.shared.security.AuthenticatedEmailResolver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@ExtendWith(MockitoExtension.class)
class ReaderChapterPageControllerTest {

    private static final UUID CHAPTER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID VOLUME_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID USER_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final String USER_EMAIL = "reader@example.com";

    @Mock
    private GetReaderChapterDetailUseCase getReaderChapterDetailUseCase;

    @Mock
    private IsChapterBookmarkedUseCase isChapterBookmarkedUseCase;

    @Mock
    private AuthenticatedEmailResolver authenticatedEmailResolver;

    @Mock
    private UserIdentityContract userIdentityContract;

    @Mock
    private Authentication authentication;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ReaderChapterPageController controller =
                new ReaderChapterPageController(
                        getReaderChapterDetailUseCase,
                        isChapterBookmarkedUseCase,
                        authenticatedEmailResolver,
                        userIdentityContract
                );

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new PublicNovelExceptionHandler())
                .build();
    }

    private ReaderChapterDetailDTO createChapterDetail(String slug) {
        ReaderVolumeSummaryDTO volume = new ReaderVolumeSummaryDTO(
                VOLUME_ID,
                "Quyển Một - Lung Trung Tước",
                "quyen-1-lung-trung-tuoc",
                1
        );

        ReaderChapterNavigationDTO next = new ReaderChapterNavigationDTO(
                2,
                "Căn Duyên",
                "chuong-2-can-duyen"
        );

        ReaderChapterTocItemDTO tocItem1 = new ReaderChapterTocItemDTO(
                1,
                "Khởi Đầu",
                slug
        );

        ReaderChapterTocItemDTO tocItem2 = new ReaderChapterTocItemDTO(
                2,
                "Căn Duyên",
                "chuong-2-can-duyen"
        );

        return new ReaderChapterDetailDTO(
                CHAPTER_ID,
                1,
                "Khởi Đầu",
                slug,
                "<p>Nội dung chương 1.</p>",
                volume,
                null,
                next,
                List.of(tocItem1, tocItem2)
        );
    }

    private UserDTO createTestUser() {
        return new UserDTO(
                USER_ID,
                USER_EMAIL,
                "Reader User",
                null,
                "ACTIVE",
                "USER",
                Instant.now()
        );
    }

    @Test
    @DisplayName("1. Anonymous Reader: Trả về 200 OK và isBookmarked=false mà không truy vấn bookmark state")
    void shouldRenderChapterReadingPageForAnonymous() throws Exception {
        String slug = "chuong-1-khoi-dau";
        ReaderChapterDetailDTO chapter = createChapterDetail(slug);

        when(getReaderChapterDetailUseCase.execute(slug)).thenReturn(chapter);
        when(authenticatedEmailResolver.resolve(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/novel/chapters/" + slug))
                .andExpect(status().isOk())
                .andExpect(view().name("novel/chapter"))
                .andExpect(model().attribute("chapter", chapter))
                .andExpect(model().attribute("pageTitle", "Chương 1: Khởi Đầu"))
                .andExpect(model().attribute("isBookmarked", false));

        verify(getReaderChapterDetailUseCase).execute(slug);
        verify(isChapterBookmarkedUseCase, never()).execute(any(), any());
    }

    @Test
    @DisplayName("2. Authenticated Reader: Gán isBookmarked=true khi người dùng đã đánh dấu chương")
    void shouldPopulateIsBookmarkedTrueWhenAuthenticatedAndBookmarked() throws Exception {
        String slug = "chuong-1-khoi-dau";
        ReaderChapterDetailDTO chapter = createChapterDetail(slug);
        UserDTO user = createTestUser();

        when(getReaderChapterDetailUseCase.execute(slug)).thenReturn(chapter);
        when(authenticatedEmailResolver.resolve(any())).thenReturn(Optional.of(USER_EMAIL));
        when(userIdentityContract.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));
        when(isChapterBookmarkedUseCase.execute(USER_ID, CHAPTER_ID)).thenReturn(true);

        mockMvc.perform(get("/novel/chapters/" + slug).principal(authentication))
                .andExpect(status().isOk())
                .andExpect(view().name("novel/chapter"))
                .andExpect(model().attribute("chapter", chapter))
                .andExpect(model().attribute("isBookmarked", true));

        verify(isChapterBookmarkedUseCase).execute(USER_ID, CHAPTER_ID);
    }

    @Test
    @DisplayName("3. Graceful Degradation: Không chặn hiển thị chương khi kiểm tra bookmark gặp lỗi bất ngờ")
    void shouldDegradeIsBookmarkedFalseWhenBookmarkLookupFails() throws Exception {
        String slug = "chuong-1-khoi-dau";
        ReaderChapterDetailDTO chapter = createChapterDetail(slug);
        UserDTO user = createTestUser();

        when(getReaderChapterDetailUseCase.execute(slug)).thenReturn(chapter);
        when(authenticatedEmailResolver.resolve(any())).thenReturn(Optional.of(USER_EMAIL));
        when(userIdentityContract.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));
        when(isChapterBookmarkedUseCase.execute(USER_ID, CHAPTER_ID))
                .thenThrow(new RuntimeException("Bookmark service transient error"));

        mockMvc.perform(get("/novel/chapters/" + slug).principal(authentication))
                .andExpect(status().isOk())
                .andExpect(view().name("novel/chapter"))
                .andExpect(model().attribute("chapter", chapter))
                .andExpect(model().attribute("isBookmarked", false));
    }

    @Test
    @DisplayName("4. 404 NOT_FOUND: Trả về HTTP 404 NOT_FOUND và view novel/not-found khi chương không tồn tại hoặc chưa xuất bản")
    void shouldReturn404WhenChapterNotFoundOrUnpublished() throws Exception {
        String slug = "chuong-khong-ton-tai";

        when(getReaderChapterDetailUseCase.execute(slug))
                .thenThrow(new ChapterNotFoundException(slug));

        mockMvc.perform(get("/novel/chapters/" + slug))
                .andExpect(status().isNotFound())
                .andExpect(view().name("novel/not-found"))
                .andExpect(model().attribute("errorTitle", "Chương không tồn tại"))
                .andExpect(model().attribute("errorMessage",
                        "Chương bạn đang tìm không tồn tại, đã bị gỡ hoặc chưa được xuất bản."));

        verify(getReaderChapterDetailUseCase).execute(slug);
    }
}
