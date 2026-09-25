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
import com.universe.wiki.application.appreciation.GetWikiAppreciationDetailStateUseCase;
import com.universe.wiki.application.appreciation.GetWikiAppreciationSummariesUseCase;
import com.universe.wiki.contracts.dto.appreciation.WikiAppreciationDetailState;
import com.universe.wiki.contracts.dto.PublishedWikiArticleListItemDTO;
import com.universe.wiki.domain.appreciation.WikiAppreciationSummary;
import com.universe.wiki.domain.article.ArticleType;

import com.universe.wiki.application.article.query.contributor.GetWikiArticlePublicContributorsUseCase;
import com.universe.wiki.contracts.dto.WikiPublicContributorDTO;
import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.wiki.entry.web.support.ArticleTypePathMapper;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Controller
@RequestMapping("/wiki")
public class PublicWikiController {

	private static final int DEFAULT_PAGE_SIZE = 20;

	private final ListPublishedWikiArticlesUseCase listPublishedArticlesUseCase;

	private final GetPublishedWikiArticleUseCase getPublishedArticleUseCase;

	private final ArticleTypePathMapper articleTypePathMapper;

	private final WikiMarkdownRenderer wikiMarkdownRenderer;

	private final IsWikiArticleSavedUseCase isWikiArticleSavedUseCase;

	private final GetWikiAppreciationDetailStateUseCase getWikiAppreciationDetailStateUseCase;

	private final GetWikiAppreciationSummariesUseCase getWikiAppreciationSummariesUseCase;

	private final UserIdentityContract userIdentityContract;

	private final GetWikiArticlePublicContributorsUseCase getWikiArticlePublicContributorsUseCase;

	@Autowired
	public PublicWikiController(ListPublishedWikiArticlesUseCase listPublishedArticlesUseCase,
			GetPublishedWikiArticleUseCase getPublishedArticleUseCase,
			ArticleTypePathMapper articleTypePathMapper,
			WikiMarkdownRenderer wikiMarkdownRenderer,
			IsWikiArticleSavedUseCase isWikiArticleSavedUseCase,
			GetWikiAppreciationDetailStateUseCase getWikiAppreciationDetailStateUseCase,
			GetWikiAppreciationSummariesUseCase getWikiAppreciationSummariesUseCase,
			UserIdentityContract userIdentityContract,
			GetWikiArticlePublicContributorsUseCase getWikiArticlePublicContributorsUseCase) {
		this.listPublishedArticlesUseCase = listPublishedArticlesUseCase;

		this.getPublishedArticleUseCase = getPublishedArticleUseCase;

		this.articleTypePathMapper = articleTypePathMapper;

		this.wikiMarkdownRenderer = wikiMarkdownRenderer;

		this.isWikiArticleSavedUseCase = Objects.requireNonNull(
				isWikiArticleSavedUseCase,
				"IsWikiArticleSavedUseCase không được để trống."
		);

		this.getWikiAppreciationDetailStateUseCase = Objects.requireNonNull(
				getWikiAppreciationDetailStateUseCase,
				"GetWikiAppreciationDetailStateUseCase không được để trống."
		);

		this.getWikiAppreciationSummariesUseCase = Objects.requireNonNull(
				getWikiAppreciationSummariesUseCase,
				"GetWikiAppreciationSummariesUseCase không được để trống."
		);

		this.userIdentityContract = userIdentityContract;

		this.getWikiArticlePublicContributorsUseCase = Objects.requireNonNull(
				getWikiArticlePublicContributorsUseCase,
				"GetWikiArticlePublicContributorsUseCase không được để trống."
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

		List<UUID> eligibleArticleIds = articlePage.items().stream()
				.filter(article -> ArticleType.CHARACTER.name().equals(article.articleType())
						|| ArticleType.FACTION.name().equals(article.articleType()))
				.map(PublishedWikiArticleListItemDTO::id)
				.toList();

		Map<UUID, WikiAppreciationSummary> appreciationSummaries = eligibleArticleIds.isEmpty()
				? Collections.emptyMap()
				: getWikiAppreciationSummariesUseCase.execute(eligibleArticleIds);

		model.addAttribute("articlePage", articlePage);

		model.addAttribute("appreciationSummaries", appreciationSummaries);

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

		boolean isAppreciationEligible = resolvedArticleType == ArticleType.CHARACTER
				|| resolvedArticleType == ArticleType.FACTION;

		WikiAppreciationDetailState appreciationState = null;
		if (isAppreciationEligible) {
			UUID viewerUserId = identityOptional
					.map(AuthenticatedRequestIdentity::userId)
					.orElse(null);
			appreciationState = getWikiAppreciationDetailStateUseCase.execute(article.id(), viewerUserId);
		}

		model.addAttribute("isAppreciationEligible", isAppreciationEligible);

		model.addAttribute("appreciationState", appreciationState);

		String attributionLine = resolveAttributionLine(article);
		model.addAttribute("attributionLine", attributionLine);

		List<WikiPublicContributorDTO> publicContributors =
				getWikiArticlePublicContributorsUseCase.execute(article.id());
		model.addAttribute("publicContributors", publicContributors);

		return "wiki/public/detail";
	}

	private String resolveAttributionLine(PublishedWikiArticleDTO article) {
		if (article == null || userIdentityContract == null) {
			return null;
		}
		if (article.contentVersion() > 1L && article.updatedBy() != null) {
			String displayName = resolveDisplayName(article.updatedBy());
			if (displayName != null && !displayName.isBlank()) {
				return "Cập nhật bởi " + displayName;
			}
		} else if (article.createdBy() != null) {
			String displayName = resolveDisplayName(article.createdBy());
			if (displayName != null && !displayName.isBlank()) {
				return "Đăng bởi " + displayName;
			}
		}
		return null;
	}

	private String resolveDisplayName(UUID userId) {
		if (userId == null) {
			return null;
		}
		try {
			Map<UUID, UserPublicProfileDTO> profiles =
					userIdentityContract.findPublicProfilesByIds(Set.of(userId));
			UserPublicProfileDTO profile = profiles.get(userId);
			if (profile != null && profile.displayName() != null && !profile.displayName().isBlank()) {
				return profile.displayName();
			}
		} catch (Exception ignored) {
			// Gracefully omit attribution if identity service lookup fails
		}
		return null;
	}

	private ArticleType resolveOptionalArticleType(String articleTypePath) {
		if (articleTypePath == null || articleTypePath.isBlank()) {
			return null;
		}

		return articleTypePathMapper.fromPath(articleTypePath);
	}
}