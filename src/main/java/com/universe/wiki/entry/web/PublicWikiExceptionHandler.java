package com.universe.wiki.entry.web;

import com.universe.wiki.application.exceptions.PublishedWikiArticleNotFoundException;
import com.universe.wiki.entry.web.support.InvalidArticleTypePathException;

import org.springframework.http.HttpStatus;

import org.springframework.ui.Model;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Xử lý exception dành riêng cho các trang Wiki công khai.
 *
 * Không áp dụng cho Admin Wiki.
 */
@ControllerAdvice(assignableTypes = PublicWikiController.class)
public class PublicWikiExceptionHandler {

	@ExceptionHandler(PublishedWikiArticleNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public String handlePublishedArticleNotFound(PublishedWikiArticleNotFoundException exception, Model model) {

		/*
		 * Không đưa exception.message trực tiếp ra UI vì message application có chứa
		 * article type + slug kỹ thuật.
		 */
		model.addAttribute("errorTitle", "Bài viết không tồn tại");

		model.addAttribute("errorMessage",
				"Bài viết bạn đang tìm không tồn tại, " + "đã được gỡ khỏi Wiki " + "hoặc chưa được xuất bản.");

		return "wiki/public/not-found";
	}

	@ExceptionHandler(InvalidArticleTypePathException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public String handleInvalidArticleTypePath(InvalidArticleTypePathException exception, Model model) {

		model.addAttribute("errorTitle", "Bài viết không tồn tại");

		model.addAttribute("errorMessage", "Đường dẫn Wiki bạn đang truy cập " + "không tồn tại hoặc không hợp lệ.");

		return "wiki/public/not-found";
	}
}