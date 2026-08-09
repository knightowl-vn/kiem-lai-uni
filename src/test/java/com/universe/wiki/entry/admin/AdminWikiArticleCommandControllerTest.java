package com.universe.wiki.entry.admin;

import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;

import com.universe.wiki.application.article.create
        .CreateAndPublishWikiArticleCommand;
import com.universe.wiki.application.article.create
        .CreateAndPublishWikiArticleUseCase;
import com.universe.wiki.application.article.create
        .CreateWikiArticleCommand;
import com.universe.wiki.application.article.create
        .CreateWikiArticleUseCase;

import com.universe.wiki.contracts.dto.WikiArticleDTO;
import com.universe.wiki.domain.article.ArticleType;

import com.universe.wiki.entry.admin.form
        .CreateWikiArticleAction;
import com.universe.wiki.entry.admin.form
        .CreateWikiArticleForm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.security.core.Authentication;

import org.springframework.web.servlet.mvc.support
        .RedirectAttributesModelMap;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.any;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminWikiArticleCommandControllerTest {

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID ARTICLE_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final String ADMIN_EMAIL =
            "admin@example.com";

    private static final Instant NOW =
            Instant.parse(
                    "2026-08-07T02:00:00Z"
            );

    @Mock
    private CreateWikiArticleUseCase
            createWikiArticleUseCase;

    @Mock
    private CreateAndPublishWikiArticleUseCase
            createAndPublishWikiArticleUseCase;

    @Mock
    private UserIdentityContract
            userIdentityContract;

    @Mock
    private Authentication
            authentication;

    private AdminWikiArticleCommandController
            controller;

    @BeforeEach
    void setUp() {
        controller =
                new AdminWikiArticleCommandController(
                        createWikiArticleUseCase,
                        createAndPublishWikiArticleUseCase,
                        userIdentityContract
                );
    }

    /*
     * =====================================================
     * SAVE DRAFT
     * =====================================================
     */

    @Test
    @DisplayName(
            "Lưu bài Wiki mới dưới dạng DRAFT"
    )
    void shouldCreateWikiDraft() {
        CreateWikiArticleForm form =
                createValidForm();

        when(
                authentication.getName()
        ).thenReturn(
                ADMIN_EMAIL
        );

        when(
                userIdentityContract.findByEmail(
                        ADMIN_EMAIL
                )
        ).thenReturn(
                Optional.of(
                        createAdminDTO()
                )
        );

        when(
                createWikiArticleUseCase.execute(
                        new CreateWikiArticleCommand(
                                "Trần Bình An",
                                ArticleType.CHARACTER,
                                "Nhân vật chính của Kiếm Lai.",
                                "Nội dung ban đầu của bài viết.",
                                "Khởi tạo bài Trần Bình An",
                                ADMIN_ID
                        )
                )
        ).thenReturn(
                createDraftArticleDTO()
        );

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        String result =
                controller.createArticle(
                        form,
                        CreateWikiArticleAction.SAVE_DRAFT,
                        authentication,
                        redirectAttributes
                );

        assertThat(result)
                .isEqualTo(
                        "redirect:/admin/wiki/articles"
                );

        assertThat(
                redirectAttributes
                        .getFlashAttributes()
                        .get(
                                "successMessage"
                        )
        ).isEqualTo(
                "Đã lưu bản nháp Wiki \"Trần Bình An\"."
        );

        verify(
                userIdentityContract
        ).findByEmail(
                ADMIN_EMAIL
        );

        verify(
                createWikiArticleUseCase
        ).execute(
                new CreateWikiArticleCommand(
                        "Trần Bình An",
                        ArticleType.CHARACTER,
                        "Nhân vật chính của Kiếm Lai.",
                        "Nội dung ban đầu của bài viết.",
                        "Khởi tạo bài Trần Bình An",
                        ADMIN_ID
                )
        );

        verify(
                createAndPublishWikiArticleUseCase,
                never()
        ).execute(
                any(CreateAndPublishWikiArticleCommand.class)
        );
    }

    /*
     * =====================================================
     * PUBLISH IMMEDIATELY
     * =====================================================
     */

    @Test
    @DisplayName(
            "Tạo và xuất bản bài Wiki ngay lập tức"
    )
    void shouldCreateAndPublishWikiArticle() {
        CreateWikiArticleForm form =
                createValidForm();

        when(
                authentication.getName()
        ).thenReturn(
                ADMIN_EMAIL
        );

        when(
                userIdentityContract.findByEmail(
                        ADMIN_EMAIL
                )
        ).thenReturn(
                Optional.of(
                        createAdminDTO()
                )
        );

        when(
                createAndPublishWikiArticleUseCase.execute(
                        new CreateAndPublishWikiArticleCommand(
                                "Trần Bình An",
                                ArticleType.CHARACTER,
                                "Nhân vật chính của Kiếm Lai.",
                                "Nội dung ban đầu của bài viết.",
                                "Khởi tạo bài Trần Bình An",
                                ADMIN_ID
                        )
                )
        ).thenReturn(
                createPublishedArticleDTO()
        );

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        String result =
                controller.createArticle(
                        form,
                        CreateWikiArticleAction.PUBLISH,
                        authentication,
                        redirectAttributes
                );

        assertThat(result)
                .isEqualTo(
                        "redirect:/admin/wiki/articles"
                );

        assertThat(
                redirectAttributes
                        .getFlashAttributes()
                        .get(
                                "successMessage"
                        )
        ).isEqualTo(
                "Đã xuất bản bài Wiki \"Trần Bình An\"."
        );

        verify(
                userIdentityContract
        ).findByEmail(
                ADMIN_EMAIL
        );

        verify(
                createAndPublishWikiArticleUseCase
        ).execute(
                new CreateAndPublishWikiArticleCommand(
                        "Trần Bình An",
                        ArticleType.CHARACTER,
                        "Nhân vật chính của Kiếm Lai.",
                        "Nội dung ban đầu của bài viết.",
                        "Khởi tạo bài Trần Bình An",
                        ADMIN_ID
                )
        );

        verify(
                createWikiArticleUseCase,
                never()
        ).execute(
                any(CreateWikiArticleCommand.class)
        );
    }

    /*
     * =====================================================
     * VALIDATION
     * =====================================================
     */

    @Test
    @DisplayName(
            "Từ chối tạo bài khi tiêu đề để trống"
    )
    void shouldRejectBlankTitle() {
        CreateWikiArticleForm form =
                new CreateWikiArticleForm();

        form.setTitle(
                "   "
        );

        form.setArticleType(
                ArticleType.CHARACTER
        );

        assertThatThrownBy(() ->
                controller.createArticle(
                        form,
                        CreateWikiArticleAction.SAVE_DRAFT,
                        authentication,
                        new RedirectAttributesModelMap()
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Tiêu đề bài Wiki không được để trống."
                );

        verify(
                userIdentityContract,
                never()
        ).findByEmail(
                any()
        );

        verify(
                createWikiArticleUseCase,
                never()
        ).execute(
                any(CreateWikiArticleCommand.class)
        );

        verify(
                createAndPublishWikiArticleUseCase,
                never()
        ).execute(
                any(CreateAndPublishWikiArticleCommand.class)
        );
    }

    @Test
    @DisplayName(
            "Từ chối khi không tìm thấy người dùng đang đăng nhập"
    )
    void shouldRejectWhenAuthenticatedUserDoesNotExist() {
        CreateWikiArticleForm form =
                createValidForm();

        when(
                authentication.getName()
        ).thenReturn(
                ADMIN_EMAIL
        );

        when(
                userIdentityContract.findByEmail(
                        ADMIN_EMAIL
                )
        ).thenReturn(
                Optional.empty()
        );

        assertThatThrownBy(() ->
                controller.createArticle(
                        form,
                        CreateWikiArticleAction.SAVE_DRAFT,
                        authentication,
                        new RedirectAttributesModelMap()
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Không tìm thấy người dùng đang đăng nhập."
                );

        verify(
                createWikiArticleUseCase,
                never()
        ).execute(
                any(CreateWikiArticleCommand.class)
        );

        verify(
                createAndPublishWikiArticleUseCase,
                never()
        ).execute(
                any(CreateAndPublishWikiArticleCommand.class)
        );
    }

    /*
     * =====================================================
     * TEST DATA
     * =====================================================
     */

    private CreateWikiArticleForm createValidForm() {
        CreateWikiArticleForm form =
                new CreateWikiArticleForm();

        /*
         * Cố tình có khoảng trắng để kiểm tra
         * Controller normalize dữ liệu.
         */
        form.setTitle(
                "  Trần Bình An  "
        );

        form.setArticleType(
                ArticleType.CHARACTER
        );

        form.setSummary(
                "  Nhân vật chính của Kiếm Lai.  "
        );

        form.setContent(
                "  Nội dung ban đầu của bài viết.  "
        );

        form.setEditSummary(
                "  Khởi tạo bài Trần Bình An  "
        );

        return form;
    }

    private UserDTO createAdminDTO() {
        return new UserDTO(
                ADMIN_ID,
                ADMIN_EMAIL,
                "Admin Wiki",
                null,
                "ACTIVE",
                "ADMIN",
                NOW
        );
    }

    private WikiArticleDTO createDraftArticleDTO() {
        return new WikiArticleDTO(
                ARTICLE_ID,
                "Trần Bình An",
                "tran-binh-an",
                "CHARACTER",
                "Nhân vật chính của Kiếm Lai.",
                "Nội dung ban đầu của bài viết.",
                "DRAFT",
                ADMIN_ID,
                ADMIN_ID,
                null,
                null,
                NOW,
                NOW,
                null,
                null,
                1L
        );
    }

    private WikiArticleDTO createPublishedArticleDTO() {
        return new WikiArticleDTO(
                ARTICLE_ID,
                "Trần Bình An",
                "tran-binh-an",
                "CHARACTER",
                "Nhân vật chính của Kiếm Lai.",
                "Nội dung ban đầu của bài viết.",
                "PUBLISHED",
                ADMIN_ID,
                ADMIN_ID,
                ADMIN_ID,
                null,
                NOW,
                NOW,
                NOW,
                null,
                1L
        );
    }
}