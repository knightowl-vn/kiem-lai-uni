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
import com.universe.novel.application.narration.GetPublicChapterNarrationPlaybackQuery;
import com.universe.novel.application.narration.GetPublicChapterNarrationPlaybackUseCase;
import com.universe.novel.application.narration.PreparePublicChapterNarrationPlaybackCommand;
import com.universe.novel.application.narration.PreparePublicChapterNarrationPlaybackResult;
import com.universe.novel.application.narration.PreparePublicChapterNarrationPlaybackUseCase;
import com.universe.novel.application.narration.PreparePublicReaderNarrationPlaybackCommand;
import com.universe.novel.application.narration.PreparePublicReaderNarrationPlaybackResult;
import com.universe.novel.application.narration.PreparePublicReaderNarrationPlaybackUseCase;
import com.universe.novel.application.narration.PrepareReaderNarrationPlaybackResult;
import com.universe.novel.application.narration.PrepareReaderNarrationSegmentOutcome;
import com.universe.novel.application.narration.PrepareReaderNarrationSegmentResult;
import com.universe.novel.application.narration.ReaderChapterNarrationPreparationDispatchStatus;
import com.universe.novel.application.narration.ReaderNarrationContinuationDispatchStatus;
import com.universe.novel.application.narration.ReaderNarrationPreparationAction;
import com.universe.novel.contracts.dto.narration.PublicChapterNarrationPlaybackAvailability;
import com.universe.novel.contracts.dto.narration.PublicChapterNarrationPlaybackCueDTO;
import com.universe.novel.contracts.dto.narration.PublicChapterNarrationPlaybackDTO;
import com.universe.novel.contracts.dto.narration.PublicChapterNarrationPlaybackFreshness;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
    private static final UUID ARTIFACT_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PreparePublicReaderNarrationPlaybackUseCase preparePublicPlaybackUseCase;

    @MockBean
    private GetPublicChapterNarrationPlaybackUseCase getPublicPlaybackUseCase;

    @MockBean
    private PreparePublicChapterNarrationPlaybackUseCase preparePublicChapterPlaybackUseCase;

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

    @Test
    @WithAnonymousUser
    @DisplayName("H.9F1 anonymous GET returns CURRENT chapter playback JSON and no-store headers")
    void shouldReturnCurrentChapterPlaybackMetadata() throws Exception {
        PublicChapterNarrationPlaybackDTO result = playbackDto(
                PublicChapterNarrationPlaybackFreshness.CURRENT,
                true,
                "/media/assets/" + MEDIA_ASSET_ID + "/content",
                List.of(new PublicChapterNarrationPlaybackCueDTO(0, SEGMENT_ID, 0, 0L, 2_000L))
        );
        when(getPublicPlaybackUseCase.execute(new GetPublicChapterNarrationPlaybackQuery(CHAPTER_ID, VOICE_KEY)))
                .thenReturn(result);

        mockMvc.perform(get("/api/novel/chapters/" + CHAPTER_ID + "/narration/playback")
                        .param("voiceKey", VOICE_KEY))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.chapterId").value(CHAPTER_ID.toString()))
                .andExpect(jsonPath("$.voiceKey").value(VOICE_KEY))
                .andExpect(jsonPath("$.availability").value("READY"))
                .andExpect(jsonPath("$.freshness").value("CURRENT"))
                .andExpect(jsonPath("$.playable").value(true))
                .andExpect(jsonPath("$.artifactId").value(ARTIFACT_ID.toString()))
                .andExpect(jsonPath("$.audioUrl").value("/media/assets/" + MEDIA_ASSET_ID + "/content"))
                .andExpect(jsonPath("$.codecMimeType").value("audio/mpeg"))
                .andExpect(jsonPath("$.durationMillis").value(4_000L))
                .andExpect(jsonPath("$.cues[0].cueOrdinal").value(0))
                .andExpect(jsonPath("$.cues[0].segmentId").value(SEGMENT_ID.toString()))
                .andExpect(jsonPath("$.cues[0].segmentIndex").value(0))
                .andExpect(jsonPath("$.cues[0].startMillis").value(0L))
                .andExpect(jsonPath("$.cues[0].endMillis").value(2_000L))
                .andExpect(jsonPath("$.managedVoiceId").doesNotExist())
                .andExpect(jsonPath("$.mediaAssetId").doesNotExist())
                .andExpect(jsonPath("$.playbackId").doesNotExist())
                .andExpect(jsonPath("$.sourceContentVersion").doesNotExist())
                .andExpect(jsonPath("$.synthesisRevision").doesNotExist());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("H.9F1 GET without voiceKey supports STALE_VOICE as playable")
    void shouldReturnPlayableStaleVoiceWithoutExplicitVoiceKey() throws Exception {
        when(getPublicPlaybackUseCase.execute(new GetPublicChapterNarrationPlaybackQuery(CHAPTER_ID, null)))
                .thenReturn(playbackDto(
                        PublicChapterNarrationPlaybackFreshness.STALE_VOICE,
                        true,
                        "/media/assets/" + MEDIA_ASSET_ID + "/content",
                        List.of(new PublicChapterNarrationPlaybackCueDTO(0, SEGMENT_ID, 0, 0L, 2_000L))
                ));

        mockMvc.perform(get("/api/novel/chapters/" + CHAPTER_ID + "/narration/playback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("READY"))
                .andExpect(jsonPath("$.freshness").value("STALE_VOICE"))
                .andExpect(jsonPath("$.playable").value(true))
                .andExpect(jsonPath("$.audioUrl").isNotEmpty());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("H.9F1 STALE_CONTENT response withholds URL, codec, duration, and cues")
    void shouldWithholdStaleContentPayload() throws Exception {
        when(getPublicPlaybackUseCase.execute(new GetPublicChapterNarrationPlaybackQuery(CHAPTER_ID, VOICE_KEY)))
                .thenReturn(new PublicChapterNarrationPlaybackDTO(
                        CHAPTER_ID,
                        VOICE_KEY,
                        PublicChapterNarrationPlaybackAvailability.READY,
                        PublicChapterNarrationPlaybackFreshness.STALE_CONTENT,
                        false,
                        ARTIFACT_ID,
                        null,
                        null,
                        null,
                        List.of()
                ));

        mockMvc.perform(get("/api/novel/chapters/" + CHAPTER_ID + "/narration/playback")
                        .param("voiceKey", VOICE_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("READY"))
                .andExpect(jsonPath("$.freshness").value("STALE_CONTENT"))
                .andExpect(jsonPath("$.playable").value(false))
                .andExpect(jsonPath("$.artifactId").value(ARTIFACT_ID.toString()))
                .andExpect(jsonPath("$.audioUrl").value(nullValue()))
                .andExpect(jsonPath("$.codecMimeType").value(nullValue()))
                .andExpect(jsonPath("$.durationMillis").value(nullValue()))
                .andExpect(jsonPath("$.cues").isEmpty());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("H.9F1 MISSING playback remains HTTP 200")
    void shouldReturnMissingPlaybackAsOk() throws Exception {
        when(getPublicPlaybackUseCase.execute(new GetPublicChapterNarrationPlaybackQuery(CHAPTER_ID, null)))
                .thenReturn(new PublicChapterNarrationPlaybackDTO(
                        CHAPTER_ID,
                        null,
                        PublicChapterNarrationPlaybackAvailability.MISSING,
                        null,
                        false,
                        null,
                        null,
                        null,
                        null,
                        List.of()
                ));

        mockMvc.perform(get("/api/novel/chapters/" + CHAPTER_ID + "/narration/playback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("MISSING"))
                .andExpect(jsonPath("$.freshness").value(nullValue()))
                .andExpect(jsonPath("$.playable").value(false))
                .andExpect(jsonPath("$.cues").isEmpty());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("H.9F1 GET maps missing Chapter and voice to 404 with no-store headers")
    void shouldReturnNotFoundForMissingChapterOrVoice() throws Exception {
        when(getPublicPlaybackUseCase.execute(any(GetPublicChapterNarrationPlaybackQuery.class)))
                .thenThrow(new ChapterNotFoundException(CHAPTER_ID));

        mockMvc.perform(get("/api/novel/chapters/" + CHAPTER_ID + "/narration/playback"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0"));

        when(getPublicPlaybackUseCase.execute(any(GetPublicChapterNarrationPlaybackQuery.class)))
                .thenThrow(new ManagedVoiceNotFoundException(VOICE_KEY));

        mockMvc.perform(get("/api/novel/chapters/" + CHAPTER_ID + "/narration/playback")
                        .param("voiceKey", VOICE_KEY))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("H.9F1 GET maps inactive voice to 400 with no-store headers")
    void shouldReturnBadRequestForInactiveVoice() throws Exception {
        when(getPublicPlaybackUseCase.execute(any(GetPublicChapterNarrationPlaybackQuery.class)))
                .thenThrow(new ManagedVoiceInvalidStateException("inactive"));

        mockMvc.perform(get("/api/novel/chapters/" + CHAPTER_ID + "/narration/playback")
                        .param("voiceKey", VOICE_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("H.9I5B POST /prepare returns 202 BUILDING when SCHEDULED")
    void shouldAcceptChapterNarrationPreparationWhenScheduled() throws Exception {
        when(preparePublicChapterPlaybackUseCase.execute(new PreparePublicChapterNarrationPlaybackCommand(CHAPTER_ID, VOICE_KEY)))
                .thenReturn(new PreparePublicChapterNarrationPlaybackResult(
                        CHAPTER_ID,
                        VOICE_KEY,
                        ReaderChapterNarrationPreparationDispatchStatus.SCHEDULED
                ));

        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_ID + "/narration/prepare")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"voiceKey\":\"" + VOICE_KEY + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, max-age=0, must-revalidate"))
                .andExpect(jsonPath("$.chapterId").value(CHAPTER_ID.toString()))
                .andExpect(jsonPath("$.voiceKey").value(VOICE_KEY))
                .andExpect(jsonPath("$.availability").value("BUILDING"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("H.9I5B POST /prepare returns 202 BUILDING when ALREADY_IN_FLIGHT")
    void shouldAcceptChapterNarrationPreparationWhenAlreadyInFlight() throws Exception {
        when(preparePublicChapterPlaybackUseCase.execute(new PreparePublicChapterNarrationPlaybackCommand(CHAPTER_ID, VOICE_KEY)))
                .thenReturn(new PreparePublicChapterNarrationPlaybackResult(
                        CHAPTER_ID,
                        VOICE_KEY,
                        ReaderChapterNarrationPreparationDispatchStatus.ALREADY_IN_FLIGHT
                ));

        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_ID + "/narration/prepare")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"voiceKey\":\"" + VOICE_KEY + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, max-age=0, must-revalidate"))
                .andExpect(jsonPath("$.chapterId").value(CHAPTER_ID.toString()))
                .andExpect(jsonPath("$.voiceKey").value(VOICE_KEY))
                .andExpect(jsonPath("$.availability").value("BUILDING"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("H.9I5B POST /prepare returns 503 Service Unavailable when REJECTED")
    void shouldReturnServiceUnavailableWhenChapterNarrationPreparationRejected() throws Exception {
        when(preparePublicChapterPlaybackUseCase.execute(new PreparePublicChapterNarrationPlaybackCommand(CHAPTER_ID, VOICE_KEY)))
                .thenReturn(new PreparePublicChapterNarrationPlaybackResult(
                        CHAPTER_ID,
                        VOICE_KEY,
                        ReaderChapterNarrationPreparationDispatchStatus.REJECTED
                ));

        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_ID + "/narration/prepare")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"voiceKey\":\"" + VOICE_KEY + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, max-age=0, must-revalidate"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("H.9I5B POST /prepare rejects anonymous request without CSRF token")
    void shouldRejectChapterNarrationPreparationWithoutCsrf() throws Exception {
        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_ID + "/narration/prepare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"voiceKey\":\"" + VOICE_KEY + "\"}"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("H.9I5B POST /prepare returns 400 when voiceKey is blank")
    void shouldReturnBadRequestWhenVoiceKeyBlank() throws Exception {
        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_ID + "/narration/prepare")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"voiceKey\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, max-age=0, must-revalidate"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("H.9I5B POST /prepare maps missing chapter or voice to 404 with no-store headers")
    void shouldReturnNotFoundWhenChapterOrVoiceNotFoundForPreparation() throws Exception {
        when(preparePublicChapterPlaybackUseCase.execute(any(PreparePublicChapterNarrationPlaybackCommand.class)))
                .thenThrow(new ChapterNotFoundException(CHAPTER_ID));

        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_ID + "/narration/prepare")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"voiceKey\":\"" + VOICE_KEY + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, max-age=0, must-revalidate"));

        when(preparePublicChapterPlaybackUseCase.execute(any(PreparePublicChapterNarrationPlaybackCommand.class)))
                .thenThrow(new ManagedVoiceNotFoundException(VOICE_KEY));

        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_ID + "/narration/prepare")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"voiceKey\":\"" + VOICE_KEY + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, max-age=0, must-revalidate"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("H.9I5B POST /prepare maps inactive voice to 400 with no-store headers")
    void shouldReturnBadRequestWhenVoiceInactiveForPreparation() throws Exception {
        when(preparePublicChapterPlaybackUseCase.execute(any(PreparePublicChapterNarrationPlaybackCommand.class)))
                .thenThrow(new ManagedVoiceInvalidStateException("inactive"));

        mockMvc.perform(post("/api/novel/chapters/" + CHAPTER_ID + "/narration/prepare")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"voiceKey\":\"" + VOICE_KEY + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, max-age=0, must-revalidate"));
    }

    private static PublicChapterNarrationPlaybackDTO playbackDto(
            PublicChapterNarrationPlaybackFreshness freshness,
            boolean playable,
            String audioUrl,
            List<PublicChapterNarrationPlaybackCueDTO> cues
    ) {
        return new PublicChapterNarrationPlaybackDTO(
                CHAPTER_ID,
                VOICE_KEY,
                PublicChapterNarrationPlaybackAvailability.READY,
                freshness,
                playable,
                ARTIFACT_ID,
                audioUrl,
                "audio/mpeg",
                4_000L,
                cues
        );
    }
}
