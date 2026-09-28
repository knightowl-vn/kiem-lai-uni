package com.universe.novel.entry.reader;

import com.universe.novel.application.locator.LocatePublishedChaptersUseCase;
import com.universe.novel.contracts.dto.locator.NovelChapterLocatorItemDTO;
import com.universe.novel.contracts.dto.locator.NovelChapterLocatorResultDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReaderNovelLocatorControllerTest — Contextual Novel Chapter Locator Web Route Tests")
class ReaderNovelLocatorControllerTest {

    @Mock
    private LocatePublishedChaptersUseCase locatePublishedChaptersUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ReaderNovelLocatorController controller = new ReaderNovelLocatorController(locatePublishedChaptersUseCase);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("1. q=167 điều hướng chính xác về /novel catalogue mở Volume và scroll tới #chapter-167")
    void locateExactChapterNumberNumericQuery() throws Exception {
        UUID chapterId = UUID.randomUUID();
        NovelChapterLocatorItemDTO item = new NovelChapterLocatorItemDTO(
                chapterId,
                167,
                "Chương 167: Kiếm Tiên",
                "chuong-167",
                2,
                "Quyển Hai"
        );
        when(locatePublishedChaptersUseCase.locate("167", 1))
                .thenReturn(new NovelChapterLocatorResultDTO("167", 167, List.of(item)));

        mockMvc.perform(get("/novel/locate").param("q", "167"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/novel?openVolume=2&locateChapter=167#chapter-167"));

        verify(locatePublishedChaptersUseCase).locate("167", 1);
    }

    @Test
    @DisplayName("2. q=chương 167 điều hướng chính xác về /novel catalogue mở Volume và scroll tới #chapter-167")
    void locateExactChapterNumberWithLowercasePrefix() throws Exception {
        UUID chapterId = UUID.randomUUID();
        NovelChapterLocatorItemDTO item = new NovelChapterLocatorItemDTO(
                chapterId,
                167,
                "Chương 167: Kiếm Tiên",
                "chuong-167",
                2,
                "Quyển Hai"
        );
        when(locatePublishedChaptersUseCase.locate("chương 167", 1))
                .thenReturn(new NovelChapterLocatorResultDTO("chương 167", 167, List.of(item)));

        mockMvc.perform(get("/novel/locate").param("q", "chương 167"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/novel?openVolume=2&locateChapter=167#chapter-167"));

        verify(locatePublishedChaptersUseCase).locate("chương 167", 1);
    }

    @Test
    @DisplayName("3. q=Chương 167 chữ hoa điều hướng chính xác về /novel catalogue")
    void locateExactChapterNumberWithCapitalizedPrefix() throws Exception {
        UUID chapterId = UUID.randomUUID();
        NovelChapterLocatorItemDTO item = new NovelChapterLocatorItemDTO(
                chapterId,
                167,
                "Chương 167: Kiếm Tiên",
                "chuong-167",
                2,
                "Quyển Hai"
        );
        when(locatePublishedChaptersUseCase.locate("Chương 167", 1))
                .thenReturn(new NovelChapterLocatorResultDTO("Chương 167", 167, List.of(item)));

        mockMvc.perform(get("/novel/locate").param("q", "Chương 167"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/novel?openVolume=2&locateChapter=167#chapter-167"));

        verify(locatePublishedChaptersUseCase).locate("Chương 167", 1);
    }

    @Test
    @DisplayName("4. q='   chương   167   ' nhiều khoảng trắng điều hướng chính xác về /novel catalogue")
    void locateExactChapterNumberWithWhitespaceVariants() throws Exception {
        UUID chapterId = UUID.randomUUID();
        NovelChapterLocatorItemDTO item = new NovelChapterLocatorItemDTO(
                chapterId,
                167,
                "Chương 167: Kiếm Tiên",
                "chuong-167",
                2,
                "Quyển Hai"
        );
        when(locatePublishedChaptersUseCase.locate("chương 167", 1))
                .thenReturn(new NovelChapterLocatorResultDTO("chương 167", 167, List.of(item)));

        mockMvc.perform(get("/novel/locate").param("q", "   chương   167   "))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/novel?openVolume=2&locateChapter=167#chapter-167"));

        verify(locatePublishedChaptersUseCase).locate("chương 167", 1);
    }

    @Test
    @DisplayName("5. Numeric locator sử dụng đúng application use case LocatePublishedChaptersUseCase")
    void usesApplicationUseCase() throws Exception {
        UUID chapterId = UUID.randomUUID();
        NovelChapterLocatorItemDTO item = new NovelChapterLocatorItemDTO(
                chapterId,
                81,
                "Chương 81: Cuối Quyển 1",
                "chuong-81",
                1,
                "Quyển Một"
        );
        when(locatePublishedChaptersUseCase.locate("81", 1))
                .thenReturn(new NovelChapterLocatorResultDTO("81", 81, List.of(item)));

        mockMvc.perform(get("/novel/locate").param("q", "81"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/novel?openVolume=1&locateChapter=81#chapter-81"));

        verify(locatePublishedChaptersUseCase).locate("81", 1);
    }

    @Test
    @DisplayName("6. Result chapter 167 được sử dụng chính xác, không dùng sai sang 1167 hay 1670")
    void resultChapterNumberIsStrictlyValidated() throws Exception {
        UUID chapterId = UUID.randomUUID();
        // Giả lập trường hợp use case trả item không khớp số chương
        NovelChapterLocatorItemDTO mismatchedItem = new NovelChapterLocatorItemDTO(
                chapterId,
                1167,
                "Chương 1167: Kiếm Tiên Khác",
                "chuong-1167",
                10,
                "Quyển Mười"
        );
        when(locatePublishedChaptersUseCase.locate("167", 1))
                .thenReturn(new NovelChapterLocatorResultDTO("167", 167, List.of(mismatchedItem)));

        mockMvc.perform(get("/novel/locate").param("q", "167"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/search?q=167&scope=novel"));
    }

    @Test
    @DisplayName("7. Không tìm thấy số chương 99999 -> fallback điều hướng sang /search?q=99999&scope=novel")
    void chapterNotFoundFallsBackToNovelSearch() throws Exception {
        when(locatePublishedChaptersUseCase.locate("chương 99999", 1))
                .thenReturn(new NovelChapterLocatorResultDTO("chương 99999", 99999, List.of()));

        mockMvc.perform(get("/novel/locate").param("q", "chương 99999"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/search?q=ch%C6%B0%C6%A1ng%2099999&scope=novel"));

        verify(locatePublishedChaptersUseCase).locate("chương 99999", 1);
    }

    @Test
    @DisplayName("8. Query văn bản thuần 'Hữu Tinh Lang' -> fallback trực tiếp sang /search?q=...&scope=novel không gọi locator DB")
    void textQueryFallsBackDirectlyToNovelSearch() throws Exception {
        mockMvc.perform(get("/novel/locate").param("q", "Hữu Tinh Lang"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/search?q=H%E1%BB%AFu%20Tinh%20Lang&scope=novel"));

        verify(locatePublishedChaptersUseCase, never()).locate(eq("Hữu Tinh Lang"), anyInt());
    }

    @Test
    @DisplayName("9. Query rỗng hoặc null -> điều hướng về /novel")
    void blankOrNullQueryRedirectsToNovelLanding() throws Exception {
        mockMvc.perform(get("/novel/locate").param("q", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/novel"));

        mockMvc.perform(get("/novel/locate").param("q", "   "))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/novel"));

        mockMvc.perform(get("/novel/locate"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/novel"));

        verify(locatePublishedChaptersUseCase, never()).locate(anyInt() + "", anyInt());
    }

    @Test
    @DisplayName("10. Tuyệt đối KHÔNG có luồng thành công nào điều hướng trực tiếp vào /novel/chapters/{slug}")
    void successNeverRedirectsToReaderDirectly() throws Exception {
        UUID chapterId = UUID.randomUUID();
        NovelChapterLocatorItemDTO item = new NovelChapterLocatorItemDTO(
                chapterId,
                1,
                "Chương 1: Khởi Đầu",
                "chuong-1",
                1,
                "Quyển Một"
        );
        when(locatePublishedChaptersUseCase.locate("1", 1))
                .thenReturn(new NovelChapterLocatorResultDTO("1", 1, List.of(item)));

        mockMvc.perform(get("/novel/locate").param("q", "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/novel/chapters/"))))
                .andExpect(redirectedUrl("/novel?openVolume=1&locateChapter=1#chapter-1"));
    }
}
