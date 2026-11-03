package com.universe.screen.entry.web;

import com.universe.screen.application.exceptions.PublishedScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.PublishedScreenTitleNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Controller advice handling exceptions for public Screen web routes.
 *
 * <p>Preserves privacy invariants: unknown, DRAFT, and ARCHIVED titles or entries return
 * consistent HTTP 404 without leaking internal publication state.
 */
@ControllerAdvice(assignableTypes = PublicScreenController.class)
public class PublicScreenExceptionHandler {

    @ExceptionHandler(PublishedScreenTitleNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handlePublishedScreenTitleNotFound(PublishedScreenTitleNotFoundException ex, Model model) {
        model.addAttribute("errorTitle", "Tác phẩm không tồn tại");
        model.addAttribute("errorMessage", "Tác phẩm bạn đang tìm không tồn tại, đã bị gỡ hoặc chưa được xuất bản.");
        model.addAttribute("activeNav", "screen");
        model.addAttribute("pageTitle", "Không tìm thấy tác phẩm - Kiếm Lai Universe");

        return "screen/not-found";
    }

    @ExceptionHandler(PublishedScreenEntryNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handlePublishedScreenEntryNotFound(PublishedScreenEntryNotFoundException ex, Model model) {
        model.addAttribute("errorTitle", "Tập phim không tồn tại");
        model.addAttribute("errorMessage", "Tập phim hoặc bản phát hành bạn đang tìm không tồn tại, đã bị gỡ hoặc chưa được xuất bản.");
        model.addAttribute("activeNav", "screen");
        model.addAttribute("pageTitle", "Không tìm thấy tập phim - Kiếm Lai Universe");

        return "screen/not-found";
    }
}
