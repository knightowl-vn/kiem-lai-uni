package com.universe.novel.entry.reader;

import com.universe.configuration.SecurityBeanConfig;
import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.identity.infrastructure.security.CustomAuthenticationFailureHandler;
import com.universe.identity.infrastructure.security.GoogleOAuthSuccessHandler;
import com.universe.novel.application.exceptions.ChapterNarrationSegmentInvalidStateException;
import com.universe.novel.application.exceptions.ChapterNarrationSegmentNotFoundException;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ManagedVoiceInvalidStateException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.narration.ChapterNarrationAudioHealthStatus;
import com.universe.novel.application.narration.PreparePublicReaderNarrationPlaybackCommand;
import com.universe.novel.application.narration.PreparePublicReaderNarrationPlaybackResult;
import com.universe.novel.application.narration.PreparePublicReaderNarrationPlaybackUseCase;
import com.universe.novel.application.narration.PrepareReaderNarrationPlaybackResult;
import com.universe.novel.application.narration.PrepareReaderNarrationSegmentOutcome;
import com.universe.novel.application.narration.PrepareReaderNarrationSegmentResult;
import com.universe.novel.application.narration.ReaderNarrationContinuationDispatchStatus;
import com.universe.novel.application.narration.ReaderNarrationPreparationAction;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PublicNovelChapterNarrationPlaybackController.class)
@Import(SecurityBeanConfig.class)
@TestPropertySource(properties = {
        "security.remember-me.key=test-remember-me-key-for-unit-test-12345",
        "security.remember-me.secure-cookie=false"
})
@DisplayName("PublicNovelChapterNarrationPlaybackController MVC Tests (MS-04.9H.7D1A)")
class PublicNovelChapterNarrationPlaybackControllerMvcTest {

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SEGMENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID VOICE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String VOICE_KEY = "kiemlai-male-01";
    private static final UUID MEDIA_ASSET_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PreparePublicReaderNarrationPlaybackUseCase preparePublicPlaybackUseCase;

    @MockBean
    private UserDetailsService userDetailsService;

    @MockBean
    private GoogleOAuthSuccessHandler googleOAuthSuccessHandler;

    @MockBean
    private AccountStatusFilter accountStatusFilter;

    @MockBean
    private CustomAuthenticationFailureHandler authenticationFailureHandler;

    @MockBean
    private ClientRegistrationRepository clientRegistrationRepository;

    @MockBean
    private CurrentUserQueryPort currentUserQueryPort;

    @MockBean
    private com.universe.shared.security.AuthenticatedEmailResolver authenticatedEmailResolver;

    @MockBean
    private com.universe.identity.contracts.interfaces.UserIdentityContract userIdentityContract;

    @BeforeEach
    void setUp() throws Exception {
        doAnswer(invocation -> {
            ServletRequest request = invocation.getArgument(0);
            ServletResponse response = invocation.getArgument(1);
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(request, response);
            return null;
        }).when(accountStatusFilter).doFilter(any(), any(), any());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("1. Anonymous user can prepare playback via canonical POST /prepare with JSON voiceKey and CSRF token")
    void shouldAllowAnonymousUserWithValidRequestBody() throws Exception {
        PrepareReaderNarrationSegmentResult immediateResult = new PrepareReaderNarrationSegmentResult(
                CHAPTER_ID,
                SEGMENT_ID,
                0,
                VOICE_ID,
                ChapterNarrationAudioHealthStatus.READY,
                ReaderNarrationPreparationAction.PLAY_NOW,
                ChapterNarrationAudioHealthStatus.READY,
                PrepareReaderNarrationSegmentOutcome.PLAYABLE_CACHED,
                MEDIA_ASSET_ID,
                false,
                false
        );
        PrepareReaderNarrationPlaybackResult internalResult = new PrepareReaderNarrationPlaybackResult(
                immediateResult,
                ReaderNarrationContinuationDispatchStatus.SCHEDULED
        );
        PreparePublicReaderNarrationPlaybackResult result = new PreparePublicReaderNarrationPlaybackResult(
                internalResult,
                VOICE_KEY
        );

        when(preparePublicPlaybackUseCase.execute(new PreparePublicReaderNarrationPlaybackCommand(CHAPTER_ID, SEGMENT_ID, VOICE_KEY)))
                .thenReturn(result);

        String jsonBody = "{\"voiceKey\": \"" + VOICE_KEY + "\"}";

        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_ID + "/narration/segments/" + SEGMENT_ID + "/prepare")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chapterId").value(CHAPTER_ID.toString()))
                .andExpect(jsonPath("$.segmentId").value(SEGMENT_ID.toString()))
                .andExpect(jsonPath("$.segmentIndex").value(0))
                .andExpect(jsonPath("$.voiceKey").value(VOICE_KEY))
                .andExpect(jsonPath("$.managedVoiceId").doesNotExist())
                .andExpect(jsonPath("$.playableNow").value(true))
                .andExpect(jsonPath("$.blocksPlayback").value(false))
                .andExpect(jsonPath("$.outcome").value("PLAYABLE_CACHED"))
                .andExpect(jsonPath("$.healthStatus").value("READY"))
                .andExpect(jsonPath("$.refreshRecommended").value(false))
                .andExpect(jsonPath("$.continuationDispatchStatus").value("SCHEDULED"))
                .andExpect(jsonPath("$.audioUrl").value("/media/assets/" + MEDIA_ASSET_ID + "/content"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("2. Removed /playback alias is not a valid endpoint for anonymous access")
    void shouldRejectRemovedPlaybackAlias() throws Exception {
        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_ID + "/narration/segments/" + SEGMENT_ID + "/playback")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"voiceKey\": \"" + VOICE_KEY + "\"}"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("3. Non-playable response returns null audioUrl and blocksPlayback = true")
    void shouldReturnNonPlayableResponseWithoutAudioUrl() throws Exception {
        PrepareReaderNarrationSegmentResult immediateResult = new PrepareReaderNarrationSegmentResult(
                CHAPTER_ID,
                SEGMENT_ID,
                0,
                VOICE_ID,
                ChapterNarrationAudioHealthStatus.FAILED,
                ReaderNarrationPreparationAction.RETRY_PREPARE,
                ChapterNarrationAudioHealthStatus.FAILED,
                PrepareReaderNarrationSegmentOutcome.RETRY_REQUIRED,
                null,
                true,
                false
        );
        PrepareReaderNarrationPlaybackResult internalResult = new PrepareReaderNarrationPlaybackResult(
                immediateResult,
                ReaderNarrationContinuationDispatchStatus.NOT_SCHEDULED
        );
        PreparePublicReaderNarrationPlaybackResult result = new PreparePublicReaderNarrationPlaybackResult(
                internalResult,
                VOICE_KEY
        );

        when(preparePublicPlaybackUseCase.execute(new PreparePublicReaderNarrationPlaybackCommand(CHAPTER_ID, SEGMENT_ID, VOICE_KEY)))
                .thenReturn(result);

        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_ID + "/narration/segments/" + SEGMENT_ID + "/prepare")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"voiceKey\": \"" + VOICE_KEY + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playableNow").value(false))
                .andExpect(jsonPath("$.blocksPlayback").value(true))
                .andExpect(jsonPath("$.outcome").value("RETRY_REQUIRED"))
                .andExpect(jsonPath("$.healthStatus").value("FAILED"))
                .andExpect(jsonPath("$.continuationDispatchStatus").value("NOT_SCHEDULED"))
                .andExpect(jsonPath("$.audioUrl").value(nullValue()));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("4. Missing CSRF token on POST is rejected with access-denied redirect")
    void shouldRejectRequestWithoutCsrfToken() throws Exception {
        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_ID + "/narration/segments/" + SEGMENT_ID + "/prepare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"voiceKey\": \"" + VOICE_KEY + "\"}"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser
    @DisplayName("5. ChapterNotFoundException returns 404 NOT_FOUND")
    void shouldReturn404WhenChapterNotFound() throws Exception {
        when(preparePublicPlaybackUseCase.execute(any(PreparePublicReaderNarrationPlaybackCommand.class)))
                .thenThrow(new ChapterNotFoundException(CHAPTER_ID));

        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_ID + "/narration/segments/" + SEGMENT_ID + "/prepare")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"voiceKey\": \"" + VOICE_KEY + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    @DisplayName("6. ManagedVoiceNotFoundException returns 404 NOT_FOUND")
    void shouldReturn404WhenVoiceNotFound() throws Exception {
        when(preparePublicPlaybackUseCase.execute(any(PreparePublicReaderNarrationPlaybackCommand.class)))
                .thenThrow(new ManagedVoiceNotFoundException(VOICE_KEY));

        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_ID + "/narration/segments/" + SEGMENT_ID + "/prepare")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"voiceKey\": \"" + VOICE_KEY + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    @DisplayName("7. ChapterNarrationSegmentNotFoundException returns 404 NOT_FOUND")
    void shouldReturn404WhenSegmentNotFound() throws Exception {
        when(preparePublicPlaybackUseCase.execute(any(PreparePublicReaderNarrationPlaybackCommand.class)))
                .thenThrow(new ChapterNarrationSegmentNotFoundException(SEGMENT_ID));

        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_ID + "/narration/segments/" + SEGMENT_ID + "/prepare")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"voiceKey\": \"" + VOICE_KEY + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    @DisplayName("8. ManagedVoiceInvalidStateException returns 400 BAD_REQUEST")
    void shouldReturn400WhenVoiceIsInvalidState() throws Exception {
        when(preparePublicPlaybackUseCase.execute(any(PreparePublicReaderNarrationPlaybackCommand.class)))
                .thenThrow(new ManagedVoiceInvalidStateException("Voice inactive"));

        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_ID + "/narration/segments/" + SEGMENT_ID + "/prepare")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"voiceKey\": \"" + VOICE_KEY + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    @DisplayName("9. ChapterNarrationSegmentInvalidStateException returns 400 BAD_REQUEST")
    void shouldReturn400WhenSegmentIsInvalidState() throws Exception {
        when(preparePublicPlaybackUseCase.execute(any(PreparePublicReaderNarrationPlaybackCommand.class)))
                .thenThrow(new ChapterNarrationSegmentInvalidStateException("Segment not CURRENT"));

        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_ID + "/narration/segments/" + SEGMENT_ID + "/prepare")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"voiceKey\": \"" + VOICE_KEY + "\"}"))
                .andExpect(status().isBadRequest());
    }
}
