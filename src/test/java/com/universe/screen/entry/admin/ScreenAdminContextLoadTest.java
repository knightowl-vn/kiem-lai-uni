package com.universe.screen.entry.admin;

import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenFranchiseMembershipRepositoryPort;
import com.universe.screen.application.ports.ScreenFranchiseRepositoryPort;
import com.universe.screen.application.ports.ScreenMediaValidationPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.application.usecase.AddScreenFranchiseMemberUseCase;
import com.universe.screen.application.usecase.ArchiveScreenEntryUseCase;
import com.universe.screen.application.usecase.ArchiveScreenTitleUseCase;
import com.universe.screen.application.usecase.CreateScreenEntryUseCase;
import com.universe.screen.application.usecase.CreateScreenFranchiseUseCase;
import com.universe.screen.application.usecase.CreateScreenTitleUseCase;
import com.universe.screen.application.usecase.GetAvailableScreenTitlesUseCase;
import com.universe.screen.application.usecase.GetScreenEntryDetailUseCase;
import com.universe.screen.application.usecase.GetScreenEntryListUseCase;
import com.universe.screen.application.usecase.GetScreenFranchiseDetailUseCase;
import com.universe.screen.application.usecase.GetScreenFranchiseListUseCase;
import com.universe.screen.application.usecase.GetScreenFranchiseMembersUseCase;
import com.universe.screen.application.usecase.GetScreenTitleDetailUseCase;
import com.universe.screen.application.usecase.GetScreenTitleListUseCase;
import com.universe.screen.application.usecase.PublishScreenEntryUseCase;
import com.universe.screen.application.usecase.PublishScreenTitleUseCase;
import com.universe.screen.application.usecase.RemoveScreenFranchiseMemberUseCase;
import com.universe.screen.application.usecase.UpdateScreenEntryUseCase;
import com.universe.screen.application.usecase.UpdateScreenFranchiseMemberOrderUseCase;
import com.universe.screen.application.usecase.UpdateScreenFranchiseUseCase;
import com.universe.screen.application.usecase.UpdateScreenTitleUseCase;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {
        AdminScreenFranchisePageController.class,
        AdminScreenFranchiseCommandController.class,
        GetScreenFranchiseListUseCase.class,
        GetScreenFranchiseDetailUseCase.class,
        GetScreenFranchiseMembersUseCase.class,
        GetAvailableScreenTitlesUseCase.class,
        CreateScreenFranchiseUseCase.class,
        UpdateScreenFranchiseUseCase.class,
        AddScreenFranchiseMemberUseCase.class,
        UpdateScreenFranchiseMemberOrderUseCase.class,
        RemoveScreenFranchiseMemberUseCase.class,
        AdminScreenTitlePageController.class,
        AdminScreenTitleCommandController.class,
        CreateScreenTitleUseCase.class,
        UpdateScreenTitleUseCase.class,
        PublishScreenTitleUseCase.class,
        ArchiveScreenTitleUseCase.class,
        GetScreenTitleListUseCase.class,
        GetScreenTitleDetailUseCase.class,
        AdminScreenEntryPageController.class,
        AdminScreenEntryCommandController.class,
        GetScreenEntryListUseCase.class,
        GetScreenEntryDetailUseCase.class,
        CreateScreenEntryUseCase.class,
        UpdateScreenEntryUseCase.class,
        PublishScreenEntryUseCase.class,
        ArchiveScreenEntryUseCase.class
})
@TestPropertySource(properties = {
        "spring.main.banner-mode=off"
})
class ScreenAdminContextLoadTest {

    @MockBean
    private ScreenFranchiseRepositoryPort franchiseRepository;

    @MockBean
    private ScreenFranchiseMembershipRepositoryPort membershipRepository;

    @MockBean
    private ScreenTitleRepositoryPort titleRepository;

    @MockBean
    private ScreenEntryRepositoryPort entryRepository;

    @MockBean
    private ScreenMediaValidationPort mediaValidationPort;

    @MockBean
    private ClockPort clockPort;

    @Autowired
    private AdminScreenFranchisePageController franchisePageController;

    @Autowired
    private AdminScreenFranchiseCommandController franchiseCommandController;

    @Autowired
    private AdminScreenTitlePageController titlePageController;

    @Autowired
    private AdminScreenTitleCommandController titleCommandController;

    @Autowired
    private GetScreenFranchiseListUseCase getScreenFranchiseListUseCase;

    @Autowired
    private CreateScreenFranchiseUseCase createScreenFranchiseUseCase;

    @Autowired
    private AddScreenFranchiseMemberUseCase addScreenFranchiseMemberUseCase;

    @Autowired
    private CreateScreenTitleUseCase createScreenTitleUseCase;

    @Autowired
    private PublishScreenTitleUseCase publishScreenTitleUseCase;

    @Autowired
    private ArchiveScreenTitleUseCase archiveScreenTitleUseCase;

    @Autowired
    private GetScreenTitleListUseCase getScreenTitleListUseCase;

    @Autowired
    private AdminScreenEntryPageController entryPageController;

    @Autowired
    private AdminScreenEntryCommandController entryCommandController;

    @Autowired
    private GetScreenEntryListUseCase getScreenEntryListUseCase;

    @Autowired
    private GetScreenEntryDetailUseCase getScreenEntryDetailUseCase;

    @Autowired
    private CreateScreenEntryUseCase createScreenEntryUseCase;

    @Autowired
    private UpdateScreenEntryUseCase updateScreenEntryUseCase;

    @Autowired
    private PublishScreenEntryUseCase publishScreenEntryUseCase;

    @Autowired
    private ArchiveScreenEntryUseCase archiveScreenEntryUseCase;

    @Test
    @DisplayName("Spring Wiring: Admin Screen controllers and use cases are successfully wired into Spring context")
    void shouldSuccessfullyWireAdminBeans() {
        assertThat(franchisePageController).isNotNull();
        assertThat(franchiseCommandController).isNotNull();
        assertThat(titlePageController).isNotNull();
        assertThat(titleCommandController).isNotNull();
        assertThat(getScreenFranchiseListUseCase).isNotNull();
        assertThat(createScreenFranchiseUseCase).isNotNull();
        assertThat(addScreenFranchiseMemberUseCase).isNotNull();
        assertThat(createScreenTitleUseCase).isNotNull();
        assertThat(publishScreenTitleUseCase).isNotNull();
        assertThat(archiveScreenTitleUseCase).isNotNull();
        assertThat(getScreenTitleListUseCase).isNotNull();
        assertThat(entryPageController).isNotNull();
        assertThat(entryCommandController).isNotNull();
        assertThat(getScreenEntryListUseCase).isNotNull();
        assertThat(getScreenEntryDetailUseCase).isNotNull();
        assertThat(createScreenEntryUseCase).isNotNull();
        assertThat(updateScreenEntryUseCase).isNotNull();
        assertThat(publishScreenEntryUseCase).isNotNull();
        assertThat(archiveScreenEntryUseCase).isNotNull();
    }
}
