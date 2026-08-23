package com.universe.novel.entry.reader;

import com.universe.novel.application.exceptions.ChapterNotFoundException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

import static org.assertj.core.api.Assertions.assertThat;

class PublicNovelExceptionHandlerTest {

    private PublicNovelExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PublicNovelExceptionHandler();
    }

    @Test
    @DisplayName("Xử lý ChapterNotFoundException trả về view novel/not-found và model error")
    void shouldHandleChapterNotFoundException() {
        ChapterNotFoundException exception =
                new ChapterNotFoundException("chuong-chua-xuat-ban");

        ExtendedModelMap model = new ExtendedModelMap();

        String view = handler.handleChapterNotFound(exception, model);

        assertThat(view).isEqualTo("novel/not-found");
        assertThat(model.getAttribute("errorTitle")).isEqualTo("Chương không tồn tại");
        assertThat(model.getAttribute("errorMessage"))
                .isEqualTo("Chương bạn đang tìm không tồn tại, đã bị gỡ hoặc chưa được xuất bản.");
    }
}
