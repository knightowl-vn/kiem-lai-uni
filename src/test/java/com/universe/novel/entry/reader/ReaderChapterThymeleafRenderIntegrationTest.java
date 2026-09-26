package com.universe.novel.entry.reader;

import com.universe.novel.contracts.dto.reader.ReaderChapterDetailDTO;
import com.universe.novel.contracts.dto.reader.ReaderChapterNavigationDTO;
import com.universe.novel.contracts.dto.reader.ReaderChapterTocItemDTO;
import com.universe.novel.contracts.dto.reader.ReaderVolumeSummaryDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.thymeleaf.extras.springsecurity6.dialect.SpringSecurityDialect;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReaderChapterThymeleafRenderIntegrationTest {

    private static final UUID CHAPTER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID VOLUME_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private SpringTemplateEngine templateEngine;
    private JakartaServletWebApplication application;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver templateResolver = new ClassLoaderTemplateResolver();
        templateResolver.setPrefix("templates/");
        templateResolver.setSuffix(".html");
        templateResolver.setTemplateMode(TemplateMode.HTML);
        templateResolver.setCharacterEncoding("UTF-8");
        templateResolver.setCacheable(false);

        MockServletContext servletContext = new MockServletContext();
        org.springframework.web.context.support.StaticWebApplicationContext applicationContext =
                new org.springframework.web.context.support.StaticWebApplicationContext();
        applicationContext.setServletContext(servletContext);
        var expressionHandler =
                new org.springframework.security.web.access.expression.DefaultWebSecurityExpressionHandler();
        expressionHandler.setApplicationContext(applicationContext);
        applicationContext.getBeanFactory().registerSingleton(
                "webSecurityExpressionHandler",
                expressionHandler
        );
        applicationContext.refresh();

        servletContext.setAttribute(
                org.springframework.web.context.WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE,
                applicationContext
        );

        templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(templateResolver);
        templateEngine.addDialect(new SpringSecurityDialect());

        application = JakartaServletWebApplication.buildApplication(servletContext);

        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.AnonymousAuthenticationToken(
                        "key",
                        "anonymousUser",
                        org.springframework.security.core.authority.AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")
                )
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private ReaderChapterDetailDTO createChapterDetail() {
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
                "chuong-1-khoi-dau"
        );

        return new ReaderChapterDetailDTO(
                CHAPTER_ID,
                1,
                "Khởi Đầu",
                "chuong-1-khoi-dau",
                "<p>Nội dung chương 1.</p>",
                1L,
                volume,
                null,
                next,
                List.of(tocItem1)
        );
    }

    private org.thymeleaf.context.IWebContext createWebContext(
            ReaderChapterDetailDTO chapter,
            boolean isBookmarked
    ) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        var webExchange = application.buildExchange(request, response);

        org.thymeleaf.context.WebContext context =
                new org.thymeleaf.context.WebContext(webExchange);

        context.setVariable("chapter", chapter);
        context.setVariable("pageTitle", "Chương 1: Khởi Đầu");
        context.setVariable("isBookmarked", isBookmarked);
        context.setVariable("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token-123"));

        return context;
    }

    @Test
    @DisplayName("1. Real Thymeleaf Render for ANONYMOUS user renders full document to </html> without chapter reactions")
    void shouldRenderFullDocumentForAnonymousWithoutChapterReactions() {
        ReaderChapterDetailDTO chapter = createChapterDetail();
        var context = createWebContext(chapter, false);

        String renderedHtml = templateEngine.process("novel/chapter", context);

        // 1. Chapter reaction section MUST NOT be present
        assertThat(renderedHtml).doesNotContain("id=\"novelChapterReactions\"");
        assertThat(renderedHtml).doesNotContain("class=\"novel-chapter-reactions\"");
        assertThat(renderedHtml).doesNotContain("data-reaction-target-type=\"NOVEL_CHAPTER\"");

        // 2. Head includes reaction stylesheet for comments
        assertThat(renderedHtml).contains("href=\"/css/shared/interaction-reactions.css\"");

        // 3. Comments section and drawer MUST be intact and transmitted
        assertThat(renderedHtml).contains("id=\"novelChapterComments\"");
        assertThat(renderedHtml).contains("id=\"novelChapterCommentsStatus\"");
        assertThat(renderedHtml).contains("id=\"novelChapterCommentsList\"");
        assertThat(renderedHtml).contains("id=\"novelBlockDiscussionDrawer\"");

        // 4. Scripts MUST be intact
        assertThat(renderedHtml).contains("src=\"/js/novel/reader-chapter-comments.js\"");
        assertThat(renderedHtml).contains("src=\"/js/novel/reader-block-discussion-drawer.js\"");
        assertThat(renderedHtml).contains("src=\"/js/shared/interaction-reactions.js\"");

        // 5. Sentinel: Closing tags reached
        assertThat(renderedHtml).contains("</body>");
        assertThat(renderedHtml).contains("</html>");
    }

    @Test
    @DisplayName("2. Real Thymeleaf Render for AUTHENTICATED user renders comment composer and completes to </html>")
    void shouldRenderAuthenticatedChapterPageWithCommentComposer() {
        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "reader@example.com",
                        "password",
                        org.springframework.security.core.authority.AuthorityUtils.createAuthorityList("ROLE_USER")
                )
        );

        ReaderChapterDetailDTO chapter = createChapterDetail();
        var context = createWebContext(chapter, true);

        String renderedHtml = templateEngine.process("novel/chapter", context);

        // Chapter reactions absent
        assertThat(renderedHtml).doesNotContain("id=\"novelChapterReactions\"");

        // Authenticated elements present
        assertThat(renderedHtml).contains("id=\"novelChapterComments\"");
        assertThat(renderedHtml).contains("id=\"novelChapterCommentComposer\"");
        assertThat(renderedHtml).contains("id=\"novelBlockDiscussionDrawer\"");

        // Closing tags reached
        assertThat(renderedHtml).contains("</body>");
        assertThat(renderedHtml).contains("</html>");
    }
}
