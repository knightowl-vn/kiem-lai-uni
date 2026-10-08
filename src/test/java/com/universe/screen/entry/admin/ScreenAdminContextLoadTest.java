package com.universe.screen.entry.admin;

import com.universe.screen.application.ports.ScreenFranchiseMembershipRepositoryPort;
import com.universe.screen.application.ports.ScreenFranchiseRepositoryPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.application.usecase.AddScreenFranchiseMemberUseCase;
import com.universe.screen.application.usecase.CreateScreenFranchiseUseCase;
import com.universe.screen.application.usecase.GetAvailableScreenTitlesUseCase;
import com.universe.screen.application.usecase.GetScreenFranchiseDetailUseCase;
import com.universe.screen.application.usecase.GetScreenFranchiseListUseCase;
import com.universe.screen.application.usecase.GetScreenFranchiseMembersUseCase;
import com.universe.screen.application.usecase.RemoveScreenFranchiseMemberUseCase;
import com.universe.screen.application.usecase.UpdateScreenFranchiseMemberOrderUseCase;
import com.universe.screen.application.usecase.UpdateScreenFranchiseUseCase;
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
        RemoveScreenFranchiseMemberUseCase.class
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

    @Autowired
    private AdminScreenFranchisePageController pageController;

    @Autowired
    private AdminScreenFranchiseCommandController commandController;

    @Autowired
    private GetScreenFranchiseListUseCase getScreenFranchiseListUseCase;

    @Autowired
    private CreateScreenFranchiseUseCase createScreenFranchiseUseCase;

    @Autowired
    private AddScreenFranchiseMemberUseCase addScreenFranchiseMemberUseCase;

    @Test
    @DisplayName("Spring Wiring: Admin Screen controllers and use cases are successfully wired into Spring context")
    void shouldSuccessfullyWireAdminBeans() {
        assertThat(pageController).isNotNull();
        assertThat(commandController).isNotNull();
        assertThat(getScreenFranchiseListUseCase).isNotNull();
        assertThat(createScreenFranchiseUseCase).isNotNull();
        assertThat(addScreenFranchiseMemberUseCase).isNotNull();
    }
}
