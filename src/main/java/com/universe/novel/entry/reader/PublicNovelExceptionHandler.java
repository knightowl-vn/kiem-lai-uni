package com.universe.novel.entry.reader;

import com.universe.novel.application.exceptions.ChapterNotFoundException;

import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Xử lý exception dành riêng cho các trang Novel Reader công khai.
 *
 * Đảm bảo các Chapter không tồn tại, DRAFT, ARCHIVED hoặc thuộc
 * Volume chưa PUBLISHED đều trả về HTTP 404 nhất quán mà không làm
 * lộ trạng thái nội bộ.
 */
@ControllerAdvice(assignableTypes = {
        ReaderChapterPageController.class,
        ReaderNovelPageController.class,
        ReaderChapterListFragmentController.class
})
public class PublicNovelExceptionHandler {

    @ExceptionHandler(ChapterNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleChapterNotFound(
            ChapterNotFoundException exception,
            Model model
    ) {
        model.addAttribute(
                "errorTitle",
                "Chương không tồn tại"
        );

        model.addAttribute(
                "errorMessage",
                "Chương bạn đang tìm không tồn tại, đã bị gỡ hoặc chưa được xuất bản."
        );

        return "novel/not-found";
    }
}
