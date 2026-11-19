package com.universe.wiki.entry.web;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityAccessor;
import com.universe.wiki.application.article.query.published.GetPublicWikiArticleDetailQuery;
import com.universe.wiki.application.article.query.published.GetPublicWikiArticleDetailUseCase;
import com.universe.wiki.application.article.query.published.GetPublicWikiCatalogOverviewQuery;
import com.universe.wiki.application.article.query.published.GetPublicWikiCatalogOverviewUseCase;
import com.universe.wiki.application.article.query.published.PublicWikiArticleDetailResult;
import com.universe.wiki.application.article.query.published.PublicWikiArticleDetailTimings;
import com.universe.wiki.application.article.query.published.PublicWikiCatalogOverviewResult;
import com.universe.wiki.application.article.query.published.PublicWikiCatalogOverviewTimings;

import com.universe.wiki.application.article.render.RenderedWikiContent;
import com.universe.wiki.application.article.render.WikiMarkdownRenderer;

import com.universe.wiki.domain.article.ArticleType;

import com.universe.wiki.contracts.dto.WikiPublicContributorsResult;
import com.universe.wiki.contracts.path.ArticleTypePathMapper;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

@Controller
@RequestMapping("/wiki")
public class PublicWikiController {

	private static final Logger logger = LoggerFactory.getLogger(PublicWikiController.class);

	private static final int DEFAULT_PAGE_SIZE = 20;

	private final GetPublicWikiCatalogOverviewUseCase getPublicWikiCatalogOverviewUseCase;

	private final GetPublicWikiArticleDetailUseCase getPublicWikiArticleDetailUseCase;

	private final ArticleTypePathMapper articleTypePathMapper;

	private final WikiMarkdownRenderer wikiMarkdownRenderer;

	private final boolean diagnosticEnabled;

	@Autowired
	public PublicWikiController(
			GetPublicWikiCatalogOverviewUseCase getPublicWikiCatalogOverviewUseCase,
			GetPublicWikiArticleDetailUseCase getPublicWikiArticleDetailUseCase,
			ArticleTypePathMapper articleTypePathMapper,
			WikiMarkdownRenderer wikiMarkdownRenderer,
			@Value("${wiki.performance.diagnostic-enabled:false}") boolean diagnosticEnabled) {
		this.getPublicWikiCatalogOverviewUseCase = Objects.requireNonNull(
				getPublicWikiCatalogOverviewUseCase,
				"GetPublicWikiCatalogOverviewUseCase không được để trống."
		);
		this.getPublicWikiArticleDetailUseCase = Objects.requireNonNull(
				getPublicWikiArticleDetailUseCase,
				"GetPublicWikiArticleDetailUseCase không được để trống."
		);
		this.articleTypePathMapper = Objects.requireNonNull(
				articleTypePathMapper,
				"ArticleTypePathMapper không được để trống."
		);
		this.wikiMarkdownRenderer = Objects.requireNonNull(
				wikiMarkdownRenderer,
				"WikiMarkdownRenderer không được để trống."
		);
		this.diagnosticEnabled = diagnosticEnabled;
	}

	public PublicWikiController(
			GetPublicWikiCatalogOverviewUseCase getPublicWikiCatalogOverviewUseCase,
			GetPublicWikiArticleDetailUseCase getPublicWikiArticleDetailUseCase,
			ArticleTypePathMapper articleTypePathMapper,
			WikiMarkdownRenderer wikiMarkdownRenderer) {
		this(
				getPublicWikiCatalogOverviewUseCase,
				getPublicWikiArticleDetailUseCase,
				articleTypePathMapper,
				wikiMarkdownRenderer,
				false
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
		long t0 = diagnosticEnabled ? System.nanoTime() : 0L;
		ArticleType articleType = resolveOptionalArticleType(articleTypePath);

		GetPublicWikiCatalogOverviewQuery query = GetPublicWikiCatalogOverviewQuery.of(
				keyword,
				articleType,
				page,
				size
		);

		PublicWikiCatalogOverviewResult result = getPublicWikiCatalogOverviewUseCase.execute(query);
		long t1 = diagnosticEnabled ? System.nanoTime() : 0L;

		PublicWikiArticlePageView pageView = PublicWikiArticlePageView.fromDTO(
				result.articlePage(),
				result.directCoverUrls()
		);

		model.addAttribute("articlePage", pageView);

		model.addAttribute("appreciationSummaries", result.appreciationSummaries());

		model.addAttribute("keyword", keyword == null ? "" : keyword);

		model.addAttribute("selectedType", articleType);

		model.addAttribute("articleTypes", ArticleType.values());
		long t2 = diagnosticEnabled ? System.nanoTime() : 0L;

		if (diagnosticEnabled) {
			PublicWikiCatalogOverviewTimings timings = result.timings();
			logger.info("[PERF-DIAGNOSTIC] /wiki index handler: total={}ms, coordinator={}ms, article_query={}ms (items={}), appreciation_query={}ms (eligible={}), media_batch_resolve={}ms (assets={}, resolved={}), model_prep={}ms",
					String.format(Locale.ROOT, "%.2f", (t2 - t0) / 1_000_000.0),
					String.format(Locale.ROOT, "%.2f", (t1 - t0) / 1_000_000.0),
					String.format(Locale.ROOT, "%.2f", timings.articleQueryNanos() / 1_000_000.0), result.articlePage().items().size(),
					String.format(Locale.ROOT, "%.2f", timings.appreciationQueryNanos() / 1_000_000.0), timings.eligibleAppreciationCount(),
					String.format(Locale.ROOT, "%.2f", timings.mediaBatchResolveNanos() / 1_000_000.0), timings.coverAssetCount(), result.directCoverUrls().size(),
					String.format(Locale.ROOT, "%.2f", (t2 - t1) / 1_000_000.0));
		}

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
		long t0 = diagnosticEnabled ? System.nanoTime() : 0L;
		ArticleType resolvedArticleType = articleTypePathMapper.fromPath(articleType);

		UUID viewerUserId = AuthenticatedRequestIdentityAccessor.find(request)
				.map(AuthenticatedRequestIdentity::userId)
				.orElse(null);

		PublicWikiArticleDetailResult result = getPublicWikiArticleDetailUseCase.execute(
				GetPublicWikiArticleDetailQuery.of(resolvedArticleType, slug, viewerUserId)
		);
		long t1 = diagnosticEnabled ? System.nanoTime() : 0L;

		RenderedWikiContent renderedContent = wikiMarkdownRenderer.render(result.article().content());
		long t2 = diagnosticEnabled ? System.nanoTime() : 0L;

		PublicWikiArticleDetailView detailView = PublicWikiArticleDetailView.fromDTO(
				result.article(),
				result.directCoverUrl()
		);

		model.addAttribute("article", detailView);

		model.addAttribute("articleTypePath", articleTypePathMapper.toPath(resolvedArticleType));

		model.addAttribute("renderedContent", renderedContent);

		model.addAttribute("isSaved", result.saved());

		model.addAttribute("isAppreciationEligible", result.appreciationEligible());

		model.addAttribute("appreciationState", result.appreciationState());

		model.addAttribute("attributionLine", result.attributionLine());

		WikiPublicContributorsResult contributorsResult = result.publicContributorsResult() != null
				? result.publicContributorsResult()
				: WikiPublicContributorsResult.empty();
		model.addAttribute("publicContributors", contributorsResult.contributors());
		model.addAttribute("publicContributorsLimitReached", contributorsResult.candidateLimitReached());
		long t3 = diagnosticEnabled ? System.nanoTime() : 0L;

		if (diagnosticEnabled) {
			PublicWikiArticleDetailTimings timings = result.timings() != null
					? result.timings()
					: PublicWikiArticleDetailTimings.empty();
			logger.info("[PERF-DIAGNOSTIC] /wiki detail handler: total={}ms, coordinator={}ms, article_query={}ms, saved_lookup={}ms (auth={}), cover_resolve={}ms (present={}, resolved={}), appreciation_query={}ms (eligible={}), attribution_lookup={}ms (resolved={}), contributors_query={}ms (count={}), markdown_render={}ms, model_prep={}ms",
					String.format(Locale.ROOT, "%.2f", (t3 - t0) / 1_000_000.0),
					String.format(Locale.ROOT, "%.2f", (t1 - t0) / 1_000_000.0),
					String.format(Locale.ROOT, "%.2f", timings.articleQueryNanos() / 1_000_000.0),
					String.format(Locale.ROOT, "%.2f", timings.savedStateQueryNanos() / 1_000_000.0), timings.authenticatedViewer(),
					String.format(Locale.ROOT, "%.2f", timings.coverResolveNanos() / 1_000_000.0), timings.coverPresent(), timings.coverResolved(),
					String.format(Locale.ROOT, "%.2f", timings.appreciationQueryNanos() / 1_000_000.0), timings.appreciationEligible(),
					String.format(Locale.ROOT, "%.2f", timings.attributionQueryNanos() / 1_000_000.0), timings.attributionResolved(),
					String.format(Locale.ROOT, "%.2f", timings.contributorsQueryNanos() / 1_000_000.0), timings.contributorCount(),
					String.format(Locale.ROOT, "%.2f", (t2 - t1) / 1_000_000.0),
					String.format(Locale.ROOT, "%.2f", (t3 - t2) / 1_000_000.0));
		}

		return "wiki/public/detail";
	}

	private ArticleType resolveOptionalArticleType(String articleTypePath) {
		if (articleTypePath == null || articleTypePath.isBlank()) {
			return null;
		}

		return articleTypePathMapper.fromPath(articleTypePath);
	}
}