package com.universe.novel.entry.admin;

import com.universe.configuration.SecurityBeanConfig;
import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.identity.infrastructure.security.CustomAuthenticationFailureHandler;
import com.universe.identity.infrastructure.security.GoogleOAuthSuccessHandler;
import com.universe.novel.application.narration.AdminGenerateChapterNarrationAudioUseCase;
import com.universe.novel.application.narration.AdminNarrationDispatchResult;
import com.universe.novel.application.narration.AdminNarrationGenerationDispatcher;
import com.universe.novel.application.narration.AdminNarrationOperationState;
import com.universe.novel.application.narration.AdminRegenerateChapterNarrationAudioUseCase;
import com.universe.novel.application.narration.GetAdminChapterNarrationOverviewResult;
import com.universe.novel.application.narration.GetAdminChapterNarrationOverviewUseCase;
import com.universe.novel.application.voice.dto.ManagedVoiceDTO;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.shared.security.AuthenticatedEmailResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminNovelChapterNarrationCommandController.class)
@Import(SecurityBeanConfig.class)
@TestPropertySource(properties = {
        "security.remember-me.key=test-remember-me-key-for-unit-test-12345",
        "security.remember-me.secure-cookie=false"
})
class AdminNovelChapterNarrationCommandControllerMvcTest {

    private static final UUID CHAPTER_ID = UUID.randomUUID();
    private static final UUID VOICE_ID = UUID.randomUUID();
    private static final String ACTION = "/admin/novel/chapters/" + CHAPTER_ID + "/narration/generate-all";

    @Autowired private MockMvc mockMvc;
    @MockBean private AdminGenerateChapterNarrationAudioUseCase generateUseCase;
    @MockBean private AdminRegenerateChapterNarrationAudioUseCase regenerateUseCase;
    @MockBean private AdminNarrationGenerationDispatcher dispatcher;
    @MockBean private GetAdminChapterNarrationOverviewUseCase overviewUseCase;
    @MockBean private UserDetailsService userDetailsService;
    @MockBean private GoogleOAuthSuccessHandler googleOAuthSuccessHandler;
    @MockBean private AccountStatusFilter accountStatusFilter;
    @MockBean private CustomAuthenticationFailureHandler authenticationFailureHandler;
    @MockBean private ClientRegistrationRepository clientRegistrationRepository;
    @MockBean private CurrentUserQueryPort currentUserQueryPort;
    @MockBean private AuthenticatedEmailResolver authenticatedEmailResolver;
    @MockBean private UserIdentityContract userIdentityContract;

    @BeforeEach
    void passThroughAccountStatusFilter() throws Exception {
        doAnswer(invocation -> {
            ServletRequest request = invocation.getArgument(0);
            ServletResponse response = invocation.getArgument(1);
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(request, response);
            return null;
        }).when(accountStatusFilter).doFilter(any(), any(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanPostAllReadyLegacyChapterWithCsrfThroughDispatcher() throws Exception {
        ChapterDTO chapter = mock(ChapterDTO.class);
        ManagedVoiceDTO voice = mock(ManagedVoiceDTO.class);
        when(chapter.status()).thenReturn("PUBLISHED");
        when(voice.status()).thenReturn("ACTIVE");
        when(overviewUseCase.execute(CHAPTER_ID, VOICE_ID)).thenReturn(new GetAdminChapterNarrationOverviewResult(
                chapter, null, List.of(voice), voice, List.of(), 3, 3, 0, 0, 0, 0, 0, 0, 0, false,
                com.universe.novel.application.narration.AdminChapterNarrationPlaybackDTO.missing()));
        when(dispatcher.dispatch(CHAPTER_ID, VOICE_ID)).thenReturn(AdminNarrationDispatchResult.started(
                AdminNarrationOperationState.running(CHAPTER_ID, VOICE_ID, Instant.now())));

        mockMvc.perform(post(ACTION).param("managedVoiceId", VOICE_ID.toString()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/novel/chapters/" + CHAPTER_ID + "/narration?voiceId=" + VOICE_ID));

        verify(dispatcher).dispatch(CHAPTER_ID, VOICE_ID);
        verifyNoInteractions(generateUseCase, regenerateUseCase);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void missingCsrfCannotDispatch() throws Exception {
        mockMvc.perform(post(ACTION).param("managedVoiceId", VOICE_ID.toString()))
                .andExpect(redirectedUrl("/access-denied"));
        verifyNoInteractions(overviewUseCase, dispatcher);
    }

    @Test
    @WithMockUser(roles = "USER")
    void nonAdminCannotDispatchEvenWithCsrf() throws Exception {
        mockMvc.perform(post(ACTION).param("managedVoiceId", VOICE_ID.toString()).with(csrf()))
                .andExpect(redirectedUrl("/access-denied"));
        verifyNoInteractions(overviewUseCase, dispatcher);
    }

    @Test
    void anonymousCannotDispatchEvenWithCsrf() throws Exception {
        mockMvc.perform(post(ACTION).param("managedVoiceId", VOICE_ID.toString()).with(csrf()))
                .andExpect(redirectedUrlPattern("**/login"));
        verifyNoInteractions(overviewUseCase, dispatcher);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getCannotTriggerWholeChapterOperation() throws Exception {
        mockMvc.perform(get(ACTION).param("managedVoiceId", VOICE_ID.toString()))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(overviewUseCase, dispatcher);
    }
}
