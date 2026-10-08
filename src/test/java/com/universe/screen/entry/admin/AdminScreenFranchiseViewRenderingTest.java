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
import com.universe.screen.application.dto.ScreenFranchiseDTO;
import com.universe.screen.application.dto.ScreenFranchiseMemberDTO;
import com.universe.screen.application.dto.ScreenTitleSummaryDTO;
import com.universe.screen.application.usecase.GetAvailableScreenTitlesUseCase;
import com.universe.screen.application.usecase.GetScreenFranchiseDetailUseCase;
import com.universe.screen.application.usecase.GetScreenFranchiseListUseCase;
import com.universe.screen.application.usecase.GetScreenFranchiseMembersUseCase;
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

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminScreenFranchisePageController.class)
@Import({SecurityBeanConfig.class, AdminScreenFranchiseViewRenderingTest.TestSecurityConfig.class})
@TestPropertySource(properties = {
        "security.remember-me.key=test-secret-key-1234567890123456",
        "security.remember-me.secure-cookie=false"
})
class AdminScreenFranchiseViewRenderingTest {

    private static final String ADMIN_EMAIL = "admin@universe.local";
    private static final UUID FRANCHISE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
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
    private GetScreenFranchiseListUseCase getScreenFranchiseListUseCase;

    @MockBean
    private GetScreenFranchiseDetailUseCase getScreenFranchiseDetailUseCase;

    @MockBean
    private GetScreenFranchiseMembersUseCase getScreenFranchiseMembersUseCase;

    @MockBean
    private GetAvailableScreenTitlesUseCase getAvailableScreenTitlesUseCase;

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
    @DisplayName("Rendering: GET /admin/screen/franchises renders franchises template correctly")
    void shouldRenderFranchisesListTemplate() throws Exception {
        ScreenFranchiseDTO dto = new ScreenFranchiseDTO(FRANCHISE_ID, "Kiếm Lai Phim", "kiem-lai-phim", 2);
        when(getScreenFranchiseListUseCase.execute()).thenReturn(List.of(dto));

        mockMvc.perform(get("/admin/screen/franchises"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Danh sách Franchise")))
                .andExpect(content().string(containsString("Kiếm Lai Phim")))
                .andExpect(content().string(containsString("kiem-lai-phim")))
                .andExpect(content().string(containsString("+ Tạo Franchise")));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Rendering: GET /admin/screen/franchises/new renders create template correctly")
    void shouldRenderFranchiseCreateTemplate() throws Exception {
        mockMvc.perform(get("/admin/screen/franchises/new"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tạo Franchise mới")))
                .andExpect(content().string(containsString("Tên Franchise")))
                .andExpect(content().string(containsString("Slug URL")));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Rendering: GET /admin/screen/franchises/{id}/edit renders edit template correctly")
    void shouldRenderFranchiseEditTemplate() throws Exception {
        ScreenFranchiseDTO dto = new ScreenFranchiseDTO(FRANCHISE_ID, "Kiếm Lai Gốc", "kiem-lai-goc", 1);
        when(getScreenFranchiseDetailUseCase.execute(FRANCHISE_ID)).thenReturn(dto);

        mockMvc.perform(get("/admin/screen/franchises/{id}/edit", FRANCHISE_ID))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Chỉnh sửa Franchise")))
                .andExpect(content().string(containsString("Kiếm Lai Gốc")))
                .andExpect(content().string(containsString("kiem-lai-goc")));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Rendering: GET /admin/screen/franchises/{id}/memberships renders memberships template correctly")
    void shouldRenderFranchiseMembershipsTemplate() throws Exception {
        ScreenFranchiseDTO franchise = new ScreenFranchiseDTO(FRANCHISE_ID, "Kiếm Lai Universe", "kiem-lai-universe", 1);
        ScreenFranchiseMemberDTO member = new ScreenFranchiseMemberDTO(
                UUID.randomUUID(), FRANCHISE_ID, TITLE_ID, "Kiếm Lai Phần 1", "kiem-lai-phan-1",
                0, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, ScreenPublicationStatus.PUBLISHED
        );
        ScreenTitleSummaryDTO available = new ScreenTitleSummaryDTO(
                UUID.randomUUID(), "Kiếm Lai Movie", "kiem-lai-movie",
                ScreenStructure.FEATURE, ScreenPresentation.LIVE_ACTION, ScreenPublicationStatus.DRAFT
        );

        when(getScreenFranchiseDetailUseCase.execute(FRANCHISE_ID)).thenReturn(franchise);
        when(getScreenFranchiseMembersUseCase.execute(FRANCHISE_ID)).thenReturn(List.of(member));
        when(getAvailableScreenTitlesUseCase.execute(FRANCHISE_ID)).thenReturn(List.of(available));

        mockMvc.perform(get("/admin/screen/franchises/{id}/memberships", FRANCHISE_ID))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Kiếm Lai Universe")))
                .andExpect(content().string(containsString("Kiếm Lai Phần 1")))
                .andExpect(content().string(containsString("kiem-lai-phan-1")))
                .andExpect(content().string(containsString("Kiếm Lai Movie")))
                .andExpect(content().string(containsString("Thêm vào Franchise")));
    }
}
