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
import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.dto.ScreenTitleDTO;
import com.universe.screen.application.usecase.GetScreenEntryDetailUseCase;
import com.universe.screen.application.usecase.GetScreenEntryListUseCase;
import com.universe.screen.application.usecase.GetScreenTitleDetailUseCase;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenPublicationStatus;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.entry.admin.form.CreateScreenEntryForm;
import com.universe.screen.entry.admin.form.EditScreenEntryForm;
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
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminScreenEntryPageController.class)
@Import({SecurityBeanConfig.class, AdminScreenEntryViewRenderingTest.TestSecurityConfig.class})
@TestPropertySource(properties = {
        "security.remember-me.key=test-secret-key-1234567890123456",
        "security.remember-me.secure-cookie=false"
})
class AdminScreenEntryViewRenderingTest {

    private static final String ADMIN_EMAIL = "admin@universe.local";
    private static final UUID TITLE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ENTRY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

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
    private GetScreenTitleDetailUseCase getScreenTitleDetailUseCase;

    @MockBean
    private GetScreenEntryListUseCase getScreenEntryListUseCase;

    @MockBean
    private GetScreenEntryDetailUseCase getScreenEntryDetailUseCase;

    private ScreenTitleDTO sampleTitle;
    private ScreenEntryDTO sampleEntry;

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

        sampleTitle = new ScreenTitleDTO(
                TITLE_ID, "kiem-lai", "Kiếm Lai", null, "Synopsis phim...",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, ScreenPublicationStatus.DRAFT, null, Instant.now(), Instant.now()
        );

        sampleEntry = new ScreenEntryDTO(
                ENTRY_ID, TITLE_ID, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1: Xuất Thế",
                "Tóm tắt tập 1...", UUID.randomUUID(), UUID.randomUUID(), 1200L, ScreenPublicationStatus.DRAFT,
                null, Instant.now(), Instant.now()
        );
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Rendering: GET entries list page renders table with badges, links, and sidebar active")
    void shouldRenderEntriesListPage() throws Exception {
        when(getScreenTitleDetailUseCase.execute(TITLE_ID)).thenReturn(sampleTitle);
        when(getScreenEntryListUseCase.execute(TITLE_ID)).thenReturn(List.of(sampleEntry));

        mockMvc.perform(get("/admin/screen/titles/{titleId}/entries", TITLE_ID))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Danh sách Screen Entry")))
                .andExpect(content().string(containsString("Kiếm Lai")))
                .andExpect(content().string(containsString("Tập 1: Xuất Thế")))
                .andExpect(content().string(containsString("tap-1")))
                .andExpect(content().string(containsString("EPISODE")))
                .andExpect(content().string(containsString("#1")))
                .andExpect(content().string(containsString("1200s")))
                .andExpect(content().string(containsString("DRAFT")))
                .andExpect(content().string(containsString("+ Thêm Screen Entry")))
                .andExpect(content().string(containsString("href=\"/admin/screen/titles\" class=\"sidebar-link  active\"")));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Rendering: GET entries list page renders empty state when list is empty")
    void shouldRenderEmptyStateOnEntriesList() throws Exception {
        when(getScreenTitleDetailUseCase.execute(TITLE_ID)).thenReturn(sampleTitle);
        when(getScreenEntryListUseCase.execute(TITLE_ID)).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/admin/screen/titles/{titleId}/entries", TITLE_ID))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Chưa có Screen Entry nào")))
                .andExpect(content().string(containsString("Tạo Screen Entry mới")));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Rendering: GET entry-create page renders form with compatible types and sidebar active")
    void shouldRenderEntryCreatePage() throws Exception {
        when(getScreenTitleDetailUseCase.execute(TITLE_ID)).thenReturn(sampleTitle);

        mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/new", TITLE_ID))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tạo Screen Entry cho")))
                .andExpect(content().string(containsString("Kiếm Lai")))
                .andExpect(content().string(containsString("Tiêu đề entry")))
                .andExpect(content().string(containsString("Slug URL")))
                .andExpect(content().string(containsString("Loại entry (Type)")))
                .andExpect(content().string(containsString("Thứ tự / Số tập (Number)")))
                .andExpect(content().string(containsString("Thời lượng (giây)")))
                .andExpect(content().string(containsString("Tạo Screen Entry")))
                .andExpect(content().string(containsString("id=\"numberRequiredMark\"")))
                .andExpect(content().string(containsString("id=\"numberHint\"")))
                .andExpect(content().string(containsString("syncNumberField")))
                .andExpect(content().string(containsString("href=\"/admin/screen/titles\" class=\"sidebar-link  active\"")));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Rendering: GET entry-create page preserves flashed form input on validation retry")
    void shouldRenderEntryCreatePageWithPreservedForm() throws Exception {
        when(getScreenTitleDetailUseCase.execute(TITLE_ID)).thenReturn(sampleTitle);

        CreateScreenEntryForm flashedForm = new CreateScreenEntryForm(
                "Tập 1: Thu nghiem do dang",
                "tap-1-dang-do",
                ScreenEntryType.EPISODE,
                1,
                "Tom tat nhap do dang can giu lai",
                1500L
        );

        mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/new", TITLE_ID)
                        .flashAttr("form", flashedForm))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"Tập 1: Thu nghiem do dang\"")))
                .andExpect(content().string(containsString("value=\"tap-1-dang-do\"")))
                .andExpect(content().string(containsString("Tom tat nhap do dang can giu lai")))
                .andExpect(content().string(containsString("value=\"1500\"")));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Rendering: GET entry-detail page renders details, publication status, and action buttons")
    void shouldRenderEntryDetailPage() throws Exception {
        when(getScreenTitleDetailUseCase.execute(TITLE_ID)).thenReturn(sampleTitle);
        when(getScreenEntryDetailUseCase.execute(TITLE_ID, ENTRY_ID)).thenReturn(sampleEntry);

        mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/{entryId}", TITLE_ID, ENTRY_ID))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("SCREEN ENTRY DETAIL")))
                .andExpect(content().string(containsString("Tập 1: Xuất Thế")))
                .andExpect(content().string(containsString("tap-1")))
                .andExpect(content().string(containsString("EPISODE")))
                .andExpect(content().string(containsString("Vòng đời xuất bản")))
                .andExpect(content().string(containsString("DRAFT (Bản nháp)")))
                .andExpect(content().string(containsString("Xuất bản")))
                .andExpect(content().string(containsString("Lưu trữ")))
                .andExpect(content().string(containsString("Chỉnh sửa")))
                .andExpect(content().string(containsString("href=\"/admin/screen/titles\" class=\"sidebar-link  active\"")));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Rendering: GET entry-edit page renders edit form and read-only media references")
    void shouldRenderEntryEditPage() throws Exception {
        when(getScreenTitleDetailUseCase.execute(TITLE_ID)).thenReturn(sampleTitle);
        when(getScreenEntryDetailUseCase.execute(TITLE_ID, ENTRY_ID)).thenReturn(sampleEntry);

        mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/{entryId}/edit", TITLE_ID, ENTRY_ID))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Chỉnh sửa Screen Entry")))
                .andExpect(content().string(containsString("Tập 1: Xuất Thế")))
                .andExpect(content().string(containsString("tap-1")))
                .andExpect(content().string(containsString("Tài nguyên Media (Read-only)")))
                .andExpect(content().string(containsString("Video Media Asset ID:")))
                .andExpect(content().string(containsString("Thumbnail Media Asset ID:")))
                .andExpect(content().string(containsString("Cập nhật Screen Entry")))
                .andExpect(content().string(containsString("id=\"numberRequiredMark\"")))
                .andExpect(content().string(containsString("id=\"numberHint\"")))
                .andExpect(content().string(containsString("syncNumberField")))
                .andExpect(content().string(containsString("href=\"/admin/screen/titles\" class=\"sidebar-link  active\"")));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Rendering: GET entry-edit page preserves flashed form input and retains read-only media references")
    void shouldRenderEntryEditPageWithPreservedForm() throws Exception {
        when(getScreenTitleDetailUseCase.execute(TITLE_ID)).thenReturn(sampleTitle);
        when(getScreenEntryDetailUseCase.execute(TITLE_ID, ENTRY_ID)).thenReturn(sampleEntry);

        EditScreenEntryForm flashedForm = new EditScreenEntryForm(
                "Tập 1 Sửa Lỗi",
                "tap-1-sua-loi",
                ScreenEntryType.EPISODE,
                2,
                "Noi dung sua doi do dang",
                1800L,
                null,
                null
        );

        mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/{entryId}/edit", TITLE_ID, ENTRY_ID)
                        .flashAttr("form", flashedForm))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"Tập 1 Sửa Lỗi\"")))
                .andExpect(content().string(containsString("value=\"tap-1-sua-loi\"")))
                .andExpect(content().string(containsString("Noi dung sua doi do dang")))
                .andExpect(content().string(containsString("value=\"1800\"")))
                .andExpect(content().string(containsString(sampleEntry.videoMediaAssetId().toString())))
                .andExpect(content().string(containsString(sampleEntry.thumbnailMediaAssetId().toString())));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Rendering: GET entry-create page displays field-level validation errors from flashed BindingResult")
    void shouldRenderEntryCreatePageWithFieldErrors() throws Exception {
        when(getScreenTitleDetailUseCase.execute(TITLE_ID)).thenReturn(sampleTitle);

        CreateScreenEntryForm form = new CreateScreenEntryForm();
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(form, "form");
        bindingResult.addError(new FieldError("form", "title", "", false, null, null, "Tiêu đề không được để trống."));
        bindingResult.addError(new FieldError("form", "number", "abc", false, null, null, "Số tập/thứ tự không hợp lệ."));

        mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/new", TITLE_ID)
                        .flashAttr("form", form)
                        .flashAttr(BindingResult.MODEL_KEY_PREFIX + "form", bindingResult))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tiêu đề không được để trống.")))
                .andExpect(content().string(containsString("Số tập/thứ tự không hợp lệ.")));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Rendering: GET entry-edit page displays field-level validation errors from flashed BindingResult")
    void shouldRenderEntryEditPageWithFieldErrors() throws Exception {
        when(getScreenTitleDetailUseCase.execute(TITLE_ID)).thenReturn(sampleTitle);
        when(getScreenEntryDetailUseCase.execute(TITLE_ID, ENTRY_ID)).thenReturn(sampleEntry);

        EditScreenEntryForm form = new EditScreenEntryForm();
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(form, "form");
        bindingResult.addError(new FieldError("form", "number", "-5", false, null, null, "Số tập/thứ tự không hợp lệ."));

        mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/{entryId}/edit", TITLE_ID, ENTRY_ID)
                        .flashAttr("form", form)
                        .flashAttr(BindingResult.MODEL_KEY_PREFIX + "form", bindingResult))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Số tập/thứ tự không hợp lệ.")));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Rendering: GET entry-create page preserves initial number value and uses safe JS init")
    void shouldRenderEntryCreatePageWithInitialNumberAndSafeScriptInit() throws Exception {
        when(getScreenTitleDetailUseCase.execute(TITLE_ID)).thenReturn(sampleTitle);

        CreateScreenEntryForm form = new CreateScreenEntryForm("Tập 5", "tap-5", ScreenEntryType.EPISODE, 5, null, null);

        mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/new", TITLE_ID)
                        .flashAttr("form", form))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"5\"")))
                .andExpect(content().string(containsString("syncNumberField(false)")))
                .andExpect(content().string(containsString("syncNumberField(true)")));
    }
}
