package com.universe.screen.entry.admin;

import com.universe.configuration.SecurityBeanConfig;
import com.universe.identity.application.oauth.GoogleOAuthUserService;
import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.identity.domain.UserRole;
import com.universe.identity.infrastructure.persistence.SpringDataUserJpaRepository;
import com.universe.identity.infrastructure.persistence.UserJpaEntity;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.identity.infrastructure.security.CustomAuthenticationFailureHandler;
import com.universe.identity.infrastructure.security.GoogleOAuthSuccessHandler;
import com.universe.identity.infrastructure.security.SafeReturnToValidator;
import com.universe.screen.application.dto.ScreenTitleDTO;
import com.universe.screen.application.usecase.GetScreenTitleDetailUseCase;
import com.universe.screen.application.usecase.GetScreenTitleListUseCase;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenPublicationStatus;
import com.universe.screen.domain.ScreenStructure;
import com.universe.shared.security.AuthenticatedEmailResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminScreenTitlePageController.class)
@Import({SecurityBeanConfig.class, AdminScreenTitleViewRenderingTest.TestSecurityConfig.class})
@TestPropertySource(properties = {
        "security.remember-me.key=test-secret-key-1234567890123456",
        "security.remember-me.secure-cookie=false"
})
class AdminScreenTitleViewRenderingTest {

    private static final String ADMIN_EMAIL = "admin@universe.local";
    private static final UUID TITLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TestConfiguration
    static class TestSecurityConfig {
        @Bean
        public GoogleOAuthSuccessHandler googleOAuthSuccessHandler() {
            return new GoogleOAuthSuccessHandler(Mockito.mock(GoogleOAuthUserService.class));
        }

        @Bean
        public CustomAuthenticationFailureHandler authenticationFailureHandler() {
            return new CustomAuthenticationFailureHandler(new SafeReturnToValidator());
        }

        @Bean
        public AccountStatusFilter accountStatusFilter(SpringDataUserJpaRepository userRepository) {
            return new AccountStatusFilter(
                    com.universe.identity.infrastructure.security.AccountStatusFilterTestSupport
                            .queryPort(userRepository)
            );
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SpringDataUserJpaRepository springDataUserJpaRepository;

    @MockBean
    private UserIdentityContract userIdentityContract;

    @MockBean
    private CurrentUserQueryPort currentUserQueryPort;

    @MockBean
    private AuthenticatedEmailResolver authenticatedEmailResolver;

    @MockBean
    private UserDetailsService userDetailsService;

    @MockBean
    private GetScreenTitleListUseCase getScreenTitleListUseCase;

    @MockBean
    private GetScreenTitleDetailUseCase getScreenTitleDetailUseCase;

    @BeforeEach
    void setUp() {
        UserJpaEntity adminEntity = new UserJpaEntity();
        adminEntity.setId(UUID.randomUUID().toString());
        adminEntity.setEmail(ADMIN_EMAIL);
        adminEntity.setDisplayName("Admin User");
        adminEntity.setPublicHandle("admin_user");
        adminEntity.setStatus("ACTIVE");
        adminEntity.setRole(UserRole.ADMIN);

        when(springDataUserJpaRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(adminEntity));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Rendering: GET /admin/screen/titles renders titles template correctly with badges")
    void shouldRenderTitlesListTemplate() throws Exception {
        ScreenTitleDTO dto = new ScreenTitleDTO(
                TITLE_ID,
                "kiem-lai-phan-1",
                "Kiếm Lai Phần 1",
                "The Great Ruler",
                "Tóm tắt...",
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                null,
                null,
                ScreenPublicationStatus.DRAFT,
                null,
                Instant.now(),
                Instant.now()
        );
        when(getScreenTitleListUseCase.execute()).thenReturn(List.of(dto));

        mockMvc.perform(get("/admin/screen/titles"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Danh sách Screen Title")))
                .andExpect(content().string(containsString("Kiếm Lai Phần 1")))
                .andExpect(content().string(containsString("kiem-lai-phan-1")))
                .andExpect(content().string(containsString("+ Tạo Screen Title")))
                .andExpect(content().string(containsString("Quản lý Screen")))
                .andExpect(content().string(not(containsString("<span>Tiêu đề Screen</span>"))))
                .andExpect(content().string(containsString("Tiêu đề Screen")))
                .andExpect(content().string(containsString("Franchise")));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Rendering: GET /admin/screen/titles/new renders title-create template correctly")
    void shouldRenderTitleCreateTemplate() throws Exception {
        mockMvc.perform(get("/admin/screen/titles/new"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tạo Screen Title mới")))
                .andExpect(content().string(containsString("Quản lý Screen")))
                .andExpect(content().string(containsString("Tiêu đề hiển thị")))
                .andExpect(content().string(containsString("Slug URL")))
                .andExpect(content().string(containsString("Tổng số tập dự kiến")))
                .andExpect(content().string(containsString("SERIES")))
                .andExpect(content().string(containsString("FEATURE")));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Rendering: GET /admin/screen/titles/{id} renders title-detail template correctly")
    void shouldRenderTitleDetailTemplate() throws Exception {
        ScreenTitleDTO dto = new ScreenTitleDTO(
                TITLE_ID,
                "kiem-lai-phan-1",
                "Kiếm Lai Phần 1",
                "The Great Ruler",
                "Tóm tắt phim...",
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                null,
                null,
                ScreenPublicationStatus.DRAFT,
                null,
                Instant.now(),
                Instant.now(),
                24
        );
        when(getScreenTitleDetailUseCase.execute(TITLE_ID)).thenReturn(dto);

        mockMvc.perform(get("/admin/screen/titles/{id}", TITLE_ID))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Chi tiết Screen Title")))
                .andExpect(content().string(containsString("Kiếm Lai Phần 1")))
                .andExpect(content().string(containsString("kiem-lai-phan-1")))
                .andExpect(content().string(containsString("Tổng số tập dự kiến")))
                .andExpect(content().string(containsString("24 tập")))
                .andExpect(content().string(containsString("Xuất bản")))
                .andExpect(content().string(containsString("Lưu trữ")));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Rendering: GET /admin/screen/titles/{id}/edit renders title-edit template correctly")
    void shouldRenderTitleEditTemplate() throws Exception {
        ScreenTitleDTO dto = new ScreenTitleDTO(
                TITLE_ID,
                "kiem-lai-phan-1",
                "Kiếm Lai Phần 1",
                "The Great Ruler",
                "Tóm tắt phim...",
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                null,
                null,
                ScreenPublicationStatus.PUBLISHED,
                Instant.now(),
                Instant.now(),
                Instant.now(),
                24
        );
        when(getScreenTitleDetailUseCase.execute(TITLE_ID)).thenReturn(dto);

        mockMvc.perform(get("/admin/screen/titles/{id}/edit", TITLE_ID))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Chỉnh sửa Screen Title")))
                .andExpect(content().string(containsString("Kiếm Lai Phần 1")))
                .andExpect(content().string(containsString("kiem-lai-phan-1")))
                .andExpect(content().string(containsString("Tổng số tập dự kiến")));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Rendering: Screen sidebar item shows active state on all Title pages")
    void shouldRenderActiveScreenSidebarItemOnTitlePages() throws Exception {
        ScreenTitleDTO dto = new ScreenTitleDTO(
                TITLE_ID, "kiem-lai-phan-1", "Kiếm Lai Phần 1", "The Great Ruler", "Tóm tắt...",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null,
                ScreenPublicationStatus.DRAFT, null, Instant.now(), Instant.now()
        );
        when(getScreenTitleListUseCase.execute()).thenReturn(List.of(dto));
        when(getScreenTitleDetailUseCase.execute(TITLE_ID)).thenReturn(dto);

        // Titles list
        mockMvc.perform(get("/admin/screen/titles"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/admin/screen/titles\" class=\"sidebar-link  active\"")));

        // Title new
        mockMvc.perform(get("/admin/screen/titles/new"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/admin/screen/titles\" class=\"sidebar-link  active\"")));

        // Title detail
        mockMvc.perform(get("/admin/screen/titles/{id}", TITLE_ID))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/admin/screen/titles\" class=\"sidebar-link  active\"")));

        // Title edit
        mockMvc.perform(get("/admin/screen/titles/{id}/edit", TITLE_ID))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/admin/screen/titles\" class=\"sidebar-link  active\"")));
    }
}
