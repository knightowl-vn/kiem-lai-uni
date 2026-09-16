package com.universe.wiki.entry.web;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityAccessor;
import com.universe.wiki.application.article.query.published.GetPublishedWikiArticleQuery;
import com.universe.wiki.application.article.query.published.GetPublishedWikiArticleUseCase;
import com.universe.wiki.application.article.query.published.ListPublishedWikiArticlesQuery;
import com.universe.wiki.application.article.query.published.ListPublishedWikiArticlesUseCase;

import com.universe.wiki.application.article.render.RenderedWikiContent;
import com.universe.wiki.application.article.render.WikiMarkdownRenderer;

import com.universe.wiki.application.saved.IsWikiArticleSavedUseCase;
import com.universe.wiki.contracts.dto.PublishedWikiArticleDTO;
import com.universe.wiki.contracts.dto.PublishedWikiArticlePageDTO;

import com.universe.wiki.domain.article.ArticleType;

import com.universe.wiki.entry.web.support.ArticleTypePathMapper;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Objects;
import java.util.Optional;

@Controller
@RequestMapping("/wiki")
public class PublicWikiController {

	private static final int DEFAULT_PAGE_SIZE = 20;

	private final ListPublishedWikiArticlesUseCase listPublishedArticlesUseCase;

	private final GetPublishedWikiArticleUseCase getPublishedArticleUseCase;

	private final ArticleTypePathMapper articleTypePathMapper;

	private final WikiMarkdownRenderer wikiMarkdownRenderer;

	private final IsWikiArticleSavedUseCase isWikiArticleSavedUseCase;

	public PublicWikiController(ListPublishedWikiArticlesUseCase listPublishedArticlesUseCase,

			GetPublishedWikiArticleUseCase getPublishedArticleUseCase,

			ArticleTypePathMapper articleTypePathMapper,

			WikiMarkdownRenderer wikiMarkdownRenderer,

			IsWikiArticleSavedUseCase isWikiArticleSavedUseCase) {
		this.listPublishedArticlesUseCase = listPublishedArticlesUseCase;

		this.getPublishedArticleUseCase = getPublishedArticleUseCase;

		this.articleTypePathMapper = articleTypePathMapper;

		this.wikiMarkdownRenderer = wikiMarkdownRenderer;

		this.isWikiArticleSavedUseCase = Objects.requireNonNull(
				isWikiArticleSavedUseCase,
				"IsWikiArticleSavedUseCase không được để trống."
		);
	}

	/**
	 * Trang danh sách và tìm kiếm Wiki công khai.
	 *
	 * Ví dụ:
	 *
	 * /wiki
	 *
	 * /wiki?keyword=Trần Bình An
	 *
	 * /wiki?type=character
	 */
	@GetMapping({ "", "/" })
	public String listPage(@RequestParam(required = false) String keyword,

			@RequestParam(name = "type", required = false) String articleTypePath,

			@RequestParam(defaultValue = "0") int page,

			@RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,

			Model model) {
		ArticleType articleType = resolveOptionalArticleType(articleTypePath);

		PublishedWikiArticlePageDTO articlePage = listPublishedArticlesUseCase
				.execute(new ListPublishedWikiArticlesQuery(keyword, articleType, page, size));

		model.addAttribute("articlePage", articlePage);

		model.addAttribute("keyword", keyword == null ? "" : keyword);

		model.addAttribute("selectedType", articleType);

		model.addAttribute("articleTypes", ArticleType.values());

		return "wiki/public/index";
	}

	/**
	 * Trang chi tiết một bài Wiki đã xuất bản.
	 *
	 * Ví dụ:
	 *
	 * /wiki/character/tran-binh-an
	 */
	@GetMapping("/{articleType}/{slug}")
	public String detailPage(@PathVariable String articleType,

			@PathVariable String slug,

			HttpServletRequest request,

			Model model) {
		ArticleType resolvedArticleType = articleTypePathMapper.fromPath(articleType);

		PublishedWikiArticleDTO article = getPublishedArticleUseCase
				.execute(new GetPublishedWikiArticleQuery(resolvedArticleType, slug));

		RenderedWikiContent renderedContent = wikiMarkdownRenderer.render(article.content());

		boolean isSaved = false;
		Optional<AuthenticatedRequestIdentity> identityOptional =
				AuthenticatedRequestIdentityAccessor.find(request);
		if (identityOptional.isPresent()) {
			isSaved = isWikiArticleSavedUseCase.execute(
					identityOptional.get().userId(),
					article.id()
			);
		}

		model.addAttribute("article", article);

		model.addAttribute("articleTypePath", articleTypePathMapper.toPath(resolvedArticleType));

		model.addAttribute("renderedContent", renderedContent);

		model.addAttribute("isSaved", isSaved);

		return "wiki/public/detail";
	}

	private ArticleType resolveOptionalArticleType(String articleTypePath) {
		if (articleTypePath == null || articleTypePath.isBlank()) {
			return null;
		}

		return articleTypePathMapper.fromPath(articleTypePath);
	}
}