package com.universe.wiki.entry.web;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityAccessor;
import com.universe.wiki.application.contribution.ListUserWikiContributionsUseCase;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.WikiArticleListItemDTO;
import com.universe.wiki.contracts.dto.contribution.UserWikiContributionItemDTO;
import com.universe.wiki.contracts.dto.contribution.UserWikiContributionPageDTO;
import com.universe.wiki.contracts.path.ArticleTypePathMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Controller xử lý việc xem danh sách đóng góp Wiki cá nhân của người dùng đã xác thực.
 *
 * Quy tắc bảo mật và hiệu năng:
 * 1. Endpoint yêu cầu xác thực người dùng;
 * 2. Actor ID (userId) luôn được trích xuất an toàn từ AuthenticatedRequestIdentityAccessor,
 *    tuyệt đối không chấp nhận tham số định danh từ request query hay headers;
 * 3. Người dùng chưa xác thực chuyển hướng về /login;
 * 4. Tuyến GET /wiki/contributions hiển thị danh sách đóng góp có phân trang (kích thước mặc định 20);
 * 5. Giải quyết tính khả dụng điều hướng bài viết (live article resolution) thông qua 1 batch query duy nhất,
 *    không phát sinh N+1 truy vấn;
 * 6. Lịch sử đóng góp bảo toàn toàn vẹn snapshot gốc bất kể trạng thái của bài viết hiện tại.
 */
@Controller
@RequestMapping("/wiki")
public class UserWikiContributionController {

    private final ListUserWikiContributionsUseCase listUserWikiContributionsUseCase;
    private final WikiArticleQueryPort wikiArticleQueryPort;
    private final ArticleTypePathMapper articleTypePathMapper;

    public UserWikiContributionController(
            ListUserWikiContributionsUseCase listUserWikiContributionsUseCase,
            WikiArticleQueryPort wikiArticleQueryPort,
            ArticleTypePathMapper articleTypePathMapper
    ) {
        this.listUserWikiContributionsUseCase = Objects.requireNonNull(
                listUserWikiContributionsUseCase,
                "ListUserWikiContributionsUseCase không được để trống."
        );
        this.wikiArticleQueryPort = Objects.requireNonNull(
                wikiArticleQueryPort,
                "WikiArticleQueryPort không được để trống."
        );
        this.articleTypePathMapper = Objects.requireNonNull(
                articleTypePathMapper,
                "ArticleTypePathMapper không được để trống."
        );
    }

    @GetMapping("/contributions")
    public String userContributionsPage(
            @RequestParam(defaultValue = "0") int page,
            HttpServletRequest request,
            Model model
    ) {
        Optional<AuthenticatedRequestIdentity> identityOptional =
                AuthenticatedRequestIdentityAccessor.find(request);
        if (identityOptional.isEmpty()) {
            return "redirect:/login";
        }

        int normalizedPage = Math.max(0, page);
        UserWikiContributionPageDTO pageDto = listUserWikiContributionsUseCase.execute(
                identityOptional.get().userId(),
                normalizedPage,
                ListUserWikiContributionsUseCase.DEFAULT_PAGE_SIZE
        );

        Set<UUID> articleIds = pageDto.items().stream()
                .map(UserWikiContributionItemDTO::articleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<UUID, WikiArticleListItemDTO> liveArticlesMap = articleIds.isEmpty()
                ? Map.of()
                : wikiArticleQueryPort.findListItemsByIds(articleIds);

        UserWikiContributionPageViewModel contributionsPage = UserWikiContributionPageViewModel.from(
                pageDto,
                liveArticlesMap,
                articleTypePathMapper
        );

        model.addAttribute("contributionsPage", contributionsPage);
        model.addAttribute("pageTitle", "Đóng góp Wiki của tôi");

        return "wiki/public/contributions";
    }
}
