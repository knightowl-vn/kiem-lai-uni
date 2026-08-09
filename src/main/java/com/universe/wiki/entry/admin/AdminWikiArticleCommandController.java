package com.universe.wiki.entry.admin;

import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;

import com.universe.wiki.application.article.create.CreateWikiArticleCommand;
import com.universe.wiki.application.article.create.CreateWikiArticleUseCase;
import com.universe.wiki.application.article.create.CreateAndPublishWikiArticleCommand;
import com.universe.wiki.application.article.create.CreateAndPublishWikiArticleUseCase;

import com.universe.wiki.entry.admin.form.CreateWikiArticleAction;

import org.springframework.web.bind.annotation.RequestParam;

import com.universe.wiki.contracts.dto.WikiArticleDTO;
import com.universe.wiki.entry.admin.form.CreateWikiArticleForm;

import org.springframework.security.core.Authentication;

import org.springframework.stereotype.Controller;

import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

@Controller
@RequestMapping("/admin/wiki/articles")
public class AdminWikiArticleCommandController {

	private final CreateWikiArticleUseCase createWikiArticleUseCase;

	private final UserIdentityContract userIdentityContract;
	
	private final CreateAndPublishWikiArticleUseCase createAndPublishWikiArticleUseCase;

	public AdminWikiArticleCommandController(
	        CreateWikiArticleUseCase createWikiArticleUseCase,
	        CreateAndPublishWikiArticleUseCase
	                createAndPublishWikiArticleUseCase,
	        UserIdentityContract userIdentityContract
	) {
	    this.createWikiArticleUseCase =
	            createWikiArticleUseCase;

	    this.createAndPublishWikiArticleUseCase =
	            createAndPublishWikiArticleUseCase;

	    this.userIdentityContract =
	            userIdentityContract;
	}

	@PostMapping
	public String createArticle(
	        @ModelAttribute("form")
	        CreateWikiArticleForm form,

	        @RequestParam("action")
	        CreateWikiArticleAction action,

	        Authentication authentication,

	        RedirectAttributes redirectAttributes
	) {
	    validateCreateForm(
	            form
	    );

	    UUID actorId =
	            resolveActorId(
	                    authentication
	            );

	    WikiArticleDTO article;

	    switch (action) {

	        case SAVE_DRAFT ->

	                article =
	                        createWikiArticleUseCase.execute(
	                                new CreateWikiArticleCommand(
	                                        form.getTitle().trim(),
	                                        form.getArticleType(),
	                                        normalizeText(
	                                                form.getSummary()
	                                        ),
	                                        normalizeText(
	                                                form.getContent()
	                                        ),
	                                        normalizeEditSummary(
	                                                form.getEditSummary()
	                                        ),
	                                        actorId
	                                )
	                        );

	        case PUBLISH ->

	                article =
	                        createAndPublishWikiArticleUseCase.execute(
	                                new CreateAndPublishWikiArticleCommand(
	                                        form.getTitle().trim(),
	                                        form.getArticleType(),
	                                        normalizeText(
	                                                form.getSummary()
	                                        ),
	                                        normalizeText(
	                                                form.getContent()
	                                        ),
	                                        normalizePublishEditSummary(
	                                                form.getEditSummary()
	                                        ),
	                                        actorId
	                                )
	                        );

	        default ->
	                throw new IllegalArgumentException(
	                        "Hành động tạo bài Wiki không hợp lệ."
	                );
	    }

	    String successMessage =
	            switch (action) {

	                case SAVE_DRAFT ->
	                        "Đã lưu bản nháp Wiki \""
	                                + article.title()
	                                + "\".";

	                case PUBLISH ->
	                        "Đã xuất bản bài Wiki \""
	                                + article.title()
	                                + "\".";
	            };

	    redirectAttributes.addFlashAttribute(
	            "successMessage",
	            successMessage
	    );

	    return "redirect:/admin/wiki/articles";
	}

	private void validateCreateForm(CreateWikiArticleForm form) {
		if (form == null) {
			throw new IllegalArgumentException("Form tạo bài Wiki không được để trống.");
		}

		if (form.getTitle() == null || form.getTitle().isBlank()) {

			throw new IllegalArgumentException("Tiêu đề bài Wiki không được để trống.");
		}

		if (form.getArticleType() == null) {
			throw new IllegalArgumentException("Loại bài Wiki không được để trống.");
		}
	}

	private UUID resolveActorId(Authentication authentication) {
		if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {

			throw new IllegalStateException("Không xác định được người dùng đang đăng nhập.");
		}

		String email = authentication.getName().trim();

		UserDTO user = userIdentityContract.findByEmail(email)
				.orElseThrow(() -> new IllegalStateException("Không tìm thấy người dùng " + "đang đăng nhập."));

		return user.id();
	}

	private String normalizeText(String value) {
		if (value == null) {
			return "";
		}

		return value.trim();
	}

	private String normalizeEditSummary(String value) {
		if (value == null || value.isBlank()) {

			return "Tạo bản nháp đầu tiên";
		}

		return value.trim();
	}
	
	private String normalizePublishEditSummary(
	        String value
	) {
	    if (value == null
	            || value.isBlank()) {

	        return "Tạo và xuất bản bài viết";
	    }

	    return value.trim();
	}
}
