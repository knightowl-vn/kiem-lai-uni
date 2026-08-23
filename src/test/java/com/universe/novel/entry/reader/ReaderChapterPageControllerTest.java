package com.universe.novel.entry.reader;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.reader.GetReaderChapterDetailUseCase;
import com.universe.novel.contracts.dto.reader.ReaderChapterDetailDTO;
import com.universe.novel.contracts.dto.reader.ReaderChapterNavigationDTO;
import com.universe.novel.contracts.dto.reader.ReaderChapterTocItemDTO;
import com.universe.novel.contracts.dto.reader.ReaderVolumeSummaryDTO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

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

    @Mock
    private GetReaderChapterDetailUseCase
            getReaderChapterDetailUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ReaderChapterPageController controller =
                new ReaderChapterPageController(
                        getReaderChapterDetailUseCase
                );

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new PublicNovelExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("GET /novel/chapters/{slug} trả về 200 OK và view novel/chapter khi chương đã xuất bản")
    void shouldRenderChapterReadingPage() throws Exception {
        String slug = "chuong-1-khoi-dau";

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

        ReaderChapterDetailDTO chapter = new ReaderChapterDetailDTO(
                CHAPTER_ID,
                1,
                "Khởi Đầu",
                slug,
                "<p>Nội dung chương 1.</p>",
                volume,
                null,
                next,
                java.util.List.of(tocItem1, tocItem2)
        );

        when(getReaderChapterDetailUseCase.execute(slug))
                .thenReturn(chapter);

        mockMvc.perform(get("/novel/chapters/" + slug))
                .andExpect(status().isOk())
                .andExpect(view().name("novel/chapter"))
                .andExpect(model().attribute("chapter", chapter))
                .andExpect(model().attribute("pageTitle", "Chương 1: Khởi Đầu"));

        verify(getReaderChapterDetailUseCase).execute(slug);
    }

    @Test
    @DisplayName("GET /novel/chapters/{missingSlug} trả về HTTP 404 NOT_FOUND và view novel/not-found khi chương không tồn tại hoặc chưa xuất bản")
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
