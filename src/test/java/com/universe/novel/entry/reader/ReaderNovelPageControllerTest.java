package com.universe.novel.entry.reader;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityTestSupport;
import com.universe.novel.application.reader.GetContinueReadingUseCase;
import com.universe.novel.application.reader.GetReaderNovelLandingUseCase;
import com.universe.novel.contracts.dto.reader.ReaderChapterNavigationDTO;
import com.universe.novel.contracts.dto.reader.ReaderContinueReadingDTO;
import com.universe.novel.contracts.dto.reader.ReaderNovelLandingDTO;
import com.universe.novel.contracts.dto.reader.ReaderNovelOverviewDTO;
import com.universe.novel.contracts.dto.reader.ReaderVolumeListItemDTO;
import org.springframework.mock.web.MockHttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReaderNovelPageControllerTest {

    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID VOLUME_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID CHAPTER_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final String USER_EMAIL = "reader@universe.local";

    @Mock
    private GetReaderNovelLandingUseCase getReaderNovelLandingUseCase;

    @Mock
    private GetContinueReadingUseCase getContinueReadingUseCase;

    private ReaderNovelPageController controller;

    @BeforeEach
    void setUp() {
        controller = new ReaderNovelPageController(
                getReaderNovelLandingUseCase,
                getContinueReadingUseCase
        );
    }

    private MockHttpServletRequest authenticatedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        AuthenticatedRequestIdentityTestSupport.attach(
                request,
                new AuthenticatedRequestIdentity(
                        USER_ID,
                        USER_EMAIL,
                        "Reader",
                        null,
                        UserStatus.ACTIVE,
                        UserRole.USER
                )
        );
        return request;
    }

    private ReaderNovelLandingDTO createSampleLanding() {
        return createSampleLanding(new ReaderChapterNavigationDTO(1, "Khởi Đầu", "chuong-1-khoi-dau"));
    }

    private ReaderNovelLandingDTO createSampleLanding(ReaderChapterNavigationDTO firstChapter) {
        ReaderNovelOverviewDTO novel = new ReaderNovelOverviewDTO(
                "Kiếm Lai",
                "kiem-lai",
                "Phong Hỏa Hí Chư Hầu",
                "Giới thiệu Kiếm Lai.",
                "/images/novel/kiem-lai.jpg",
                "ONGOING"
        );

        ReaderVolumeListItemDTO volume = new ReaderVolumeListItemDTO(
                VOLUME_ID,
                "Quyển Một - Lung Trung Tước",
                "quyen-1",
                1,
                81L
        );

        return new ReaderNovelLandingDTO(novel, List.of(volume), firstChapter);
    }

    @Test
    @DisplayName("Anonymous user: renders landing page with novel, volumes & firstChapter, without continueReading attribute")
    void shouldShowReaderNovelLandingPageForAnonymousUser() {
        ReaderNovelLandingDTO landing = createSampleLanding();
        when(getReaderNovelLandingUseCase.execute()).thenReturn(landing);

        ExtendedModelMap model = new ExtendedModelMap();
        String viewName = controller.landingPage(new MockHttpServletRequest(), model);

        assertThat(viewName).isEqualTo("novel/index");
        assertThat(model.getAttribute("novel")).isEqualTo(landing.novel());
        assertThat(model.getAttribute("volumes")).isEqualTo(landing.volumes());
        assertThat(model.getAttribute("firstChapter")).isEqualTo(landing.firstChapter());
        assertThat(model.getAttribute("pageTitle")).isEqualTo("Kiếm Lai");
        assertThat(model.getAttribute("continueReading")).isNull();

        verify(getReaderNovelLandingUseCase).execute();
        verifyNoInteractions(getContinueReadingUseCase);
    }

    @Test
    @DisplayName("Authenticated user without progress: renders landing page without continueReading attribute")
    void shouldShowReaderNovelLandingForAuthenticatedUserWithoutProgress() {
        ReaderNovelLandingDTO landing = createSampleLanding();

        when(getReaderNovelLandingUseCase.execute()).thenReturn(landing);
        when(getContinueReadingUseCase.execute(USER_ID)).thenReturn(Optional.empty());

        ExtendedModelMap model = new ExtendedModelMap();
        String viewName = controller.landingPage(authenticatedRequest(), model);

        assertThat(viewName).isEqualTo("novel/index");
        assertThat(model.getAttribute("continueReading")).isNull();

        verify(getContinueReadingUseCase).execute(USER_ID);
    }

    @Test
    @DisplayName("Authenticated user with progress: renders landing page with continueReading DTO in model")
    void shouldShowReaderNovelLandingForAuthenticatedUserWithProgress() {
        ReaderNovelLandingDTO landing = createSampleLanding();
        ReaderContinueReadingDTO continueReading = new ReaderContinueReadingDTO(
                CHAPTER_ID,
                10,
                "Chương 10: Khởi Đầu",
                "chuong-10-khoi-dau",
                20
        );

        when(getReaderNovelLandingUseCase.execute()).thenReturn(landing);
        when(getContinueReadingUseCase.execute(USER_ID)).thenReturn(Optional.of(continueReading));

        ExtendedModelMap model = new ExtendedModelMap();
        String viewName = controller.landingPage(authenticatedRequest(), model);

        assertThat(viewName).isEqualTo("novel/index");
        assertThat(model.getAttribute("continueReading")).isEqualTo(continueReading);

        verify(getContinueReadingUseCase).execute(USER_ID);
    }

    @Test
    @DisplayName("Authenticated user + unexpected GetContinueReadingUseCase failure: renders landing page gracefully with Start Reading and no continueReading in model")
    void shouldShowLandingPageWithStartReadingWhenGetContinueReadingFailsUnexpectedly() {
        ReaderNovelLandingDTO landing = createSampleLanding();

        when(getReaderNovelLandingUseCase.execute()).thenReturn(landing);
        when(getContinueReadingUseCase.execute(USER_ID)).thenThrow(new RuntimeException("Database timeout or unexpected error"));

        ExtendedModelMap model = new ExtendedModelMap();
        String viewName = controller.landingPage(authenticatedRequest(), model);

        assertThat(viewName).isEqualTo("novel/index");
        assertThat(model.getAttribute("novel")).isEqualTo(landing.novel());
        assertThat(model.getAttribute("volumes")).isEqualTo(landing.volumes());
        assertThat(model.getAttribute("pageTitle")).isEqualTo("Kiếm Lai");
        assertThat(model.getAttribute("continueReading")).isNull();

        verify(getContinueReadingUseCase).execute(USER_ID);
    }
}
