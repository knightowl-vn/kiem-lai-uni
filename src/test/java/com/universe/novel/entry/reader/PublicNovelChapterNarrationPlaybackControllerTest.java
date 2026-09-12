package com.universe.novel.entry.reader;

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
import com.universe.novel.contracts.dto.narration.PrepareChapterNarrationPlaybackRequest;
import com.universe.novel.contracts.dto.narration.PrepareReaderNarrationPlaybackRequest;
import com.universe.novel.contracts.dto.narration.PublicChapterNarrationPlaybackAvailability;
import com.universe.novel.contracts.dto.narration.PublicChapterNarrationPlaybackDTO;
import com.universe.novel.contracts.dto.narration.PublicChapterNarrationPrepareResponseDTO;
import com.universe.novel.contracts.dto.narration.PublicReaderNarrationPlaybackDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PublicNovelChapterNarrationPlaybackController Unit Tests (MS-04.9H.7D1A)")
class PublicNovelChapterNarrationPlaybackControllerTest {

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SEGMENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID VOICE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String VOICE_KEY = "kiemlai-male-01";
    private static final UUID MEDIA_ASSET_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock
    private PreparePublicReaderNarrationPlaybackUseCase preparePublicPlaybackUseCase;

    @Mock
    private GetPublicChapterNarrationPlaybackUseCase getPublicPlaybackUseCase;

    @Mock
    private PreparePublicChapterNarrationPlaybackUseCase preparePublicChapterPlaybackUseCase;

    private PublicNovelChapterNarrationPlaybackController controller;

    @BeforeEach
    void setUp() {
        controller = new PublicNovelChapterNarrationPlaybackController(
                preparePublicPlaybackUseCase,
                getPublicPlaybackUseCase,
                preparePublicChapterPlaybackUseCase
        );
    }

    @Test
    @DisplayName("H.9F1 playback metadata GET delegates passively and uses manifest-equivalent cache headers")
    void shouldReturnChapterPlaybackMetadataWithoutCaching() {
        PublicChapterNarrationPlaybackDTO expected = new PublicChapterNarrationPlaybackDTO(
                CHAPTER_ID,
                VOICE_KEY,
                PublicChapterNarrationPlaybackAvailability.MISSING,
                null,
                false,
                null,
                null,
                null,
                null,
                List.of()
        );
        when(getPublicPlaybackUseCase.execute(new GetPublicChapterNarrationPlaybackQuery(CHAPTER_ID, VOICE_KEY)))
                .thenReturn(expected);
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        ResponseEntity<PublicChapterNarrationPlaybackDTO> response = controller.getChapterPlayback(
                CHAPTER_ID,
                VOICE_KEY,
                servletResponse
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(expected);
        assertThat(servletResponse.getHeader("Cache-Control"))
                .isEqualTo("no-store, no-cache, must-revalidate, max-age=0");
        assertThat(servletResponse.getHeader("Pragma")).isEqualTo("no-cache");
        assertThat(servletResponse.getDateHeader("Expires")).isZero();
        verify(getPublicPlaybackUseCase).execute(new GetPublicChapterNarrationPlaybackQuery(CHAPTER_ID, VOICE_KEY));
    }

    @Test
    @DisplayName("1. READY playable -> returns safe browser playback URL and public DTO with voiceKey")
    void shouldReturnPlayableReadyResult() {
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
        PreparePublicReaderNarrationPlaybackResult publicResult = new PreparePublicReaderNarrationPlaybackResult(
                internalResult,
                VOICE_KEY
        );

        when(preparePublicPlaybackUseCase.execute(new PreparePublicReaderNarrationPlaybackCommand(CHAPTER_ID, SEGMENT_ID, VOICE_KEY)))
                .thenReturn(publicResult);

        ResponseEntity<PublicReaderNarrationPlaybackDTO> response = controller.prepareSegmentPlayback(
                CHAPTER_ID,
                SEGMENT_ID,
                new PrepareReaderNarrationPlaybackRequest(VOICE_KEY)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PublicReaderNarrationPlaybackDTO dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.chapterId()).isEqualTo(CHAPTER_ID);
        assertThat(dto.segmentId()).isEqualTo(SEGMENT_ID);
        assertThat(dto.segmentIndex()).isEqualTo(0);
        assertThat(dto.voiceKey()).isEqualTo(VOICE_KEY);
        assertThat(dto.playableNow()).isTrue();
        assertThat(dto.blocksPlayback()).isFalse();
        assertThat(dto.outcome()).isEqualTo("PLAYABLE_CACHED");
        assertThat(dto.healthStatus()).isEqualTo("READY");
        assertThat(dto.refreshRecommended()).isFalse();
        assertThat(dto.continuationDispatchStatus()).isEqualTo("SCHEDULED");
        assertThat(dto.audioUrl()).isEqualTo("/media/assets/" + MEDIA_ASSET_ID + "/content");

        verify(preparePublicPlaybackUseCase).execute(new PreparePublicReaderNarrationPlaybackCommand(CHAPTER_ID, SEGMENT_ID, VOICE_KEY));
    }

    @Test
    @DisplayName("2. OUTDATED playable -> returns playback URL + refreshRecommended = true")
    void shouldReturnPlayableOutdatedResult() {
        PrepareReaderNarrationSegmentResult immediateResult = new PrepareReaderNarrationSegmentResult(
                CHAPTER_ID,
                SEGMENT_ID,
                2,
                VOICE_ID,
                ChapterNarrationAudioHealthStatus.OUTDATED,
                ReaderNarrationPreparationAction.PLAY_NOW_AND_REFRESH,
                ChapterNarrationAudioHealthStatus.OUTDATED,
                PrepareReaderNarrationSegmentOutcome.PLAYABLE_CACHED,
                MEDIA_ASSET_ID,
                false,
                true
        );
        PrepareReaderNarrationPlaybackResult internalResult = new PrepareReaderNarrationPlaybackResult(
                immediateResult,
                ReaderNarrationContinuationDispatchStatus.SCHEDULED
        );
        PreparePublicReaderNarrationPlaybackResult publicResult = new PreparePublicReaderNarrationPlaybackResult(
                internalResult,
                VOICE_KEY
        );

        when(preparePublicPlaybackUseCase.execute(new PreparePublicReaderNarrationPlaybackCommand(CHAPTER_ID, SEGMENT_ID, VOICE_KEY)))
                .thenReturn(publicResult);

        ResponseEntity<PublicReaderNarrationPlaybackDTO> response = controller.prepareSegmentPlayback(
                CHAPTER_ID,
                SEGMENT_ID,
                new PrepareReaderNarrationPlaybackRequest(VOICE_KEY)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PublicReaderNarrationPlaybackDTO dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.playableNow()).isTrue();
        assertThat(dto.blocksPlayback()).isFalse();
        assertThat(dto.outcome()).isEqualTo("PLAYABLE_CACHED");
        assertThat(dto.healthStatus()).isEqualTo("OUTDATED");
        assertThat(dto.refreshRecommended()).isTrue();
        assertThat(dto.audioUrl()).isEqualTo("/media/assets/" + MEDIA_ASSET_ID + "/content");
    }

    @Test
    @DisplayName("3. Non-playable (RETRY_REQUIRED, PREPARATION_FAILED, UNAVAILABLE) -> returns null audioUrl and blocksPlayback = true")
    void shouldReturnNonPlayableResultWithoutAudioUrl() {
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
        PreparePublicReaderNarrationPlaybackResult publicResult = new PreparePublicReaderNarrationPlaybackResult(
                internalResult,
                VOICE_KEY
        );

        when(preparePublicPlaybackUseCase.execute(new PreparePublicReaderNarrationPlaybackCommand(CHAPTER_ID, SEGMENT_ID, VOICE_KEY)))
                .thenReturn(publicResult);

        ResponseEntity<PublicReaderNarrationPlaybackDTO> response = controller.prepareSegmentPlayback(
                CHAPTER_ID,
                SEGMENT_ID,
                new PrepareReaderNarrationPlaybackRequest(VOICE_KEY)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PublicReaderNarrationPlaybackDTO dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.playableNow()).isFalse();
        assertThat(dto.blocksPlayback()).isTrue();
        assertThat(dto.outcome()).isEqualTo("RETRY_REQUIRED");
        assertThat(dto.healthStatus()).isEqualTo("FAILED");
        assertThat(dto.continuationDispatchStatus()).isEqualTo("NOT_SCHEDULED");
        assertThat(dto.audioUrl()).isNull();
    }

    @Test
    @DisplayName("4. Missing or blank voiceKey returns 400 Bad Request")
    void shouldReturnBadRequestWhenVoiceKeyIsMissingOrBlank() {
        ResponseEntity<PublicReaderNarrationPlaybackDTO> response1 = controller.prepareSegmentPlayback(
                CHAPTER_ID,
                SEGMENT_ID,
                null
        );
        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<PublicReaderNarrationPlaybackDTO> response2 = controller.prepareSegmentPlayback(
                CHAPTER_ID,
                SEGMENT_ID,
                new PrepareReaderNarrationPlaybackRequest("   ")
        );
        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("5. Exception handlers translate domain exceptions into correct HTTP status codes")
    void shouldTranslateDomainExceptionsToHttpStatusCodes() {
        assertThat(controller.handleChapterNotFound().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.handleVoiceNotFound().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.handleSegmentNotFound().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.handleVoiceInvalidState().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.handleSegmentInvalidState().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.handleIllegalArgument().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.handleIllegalState().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.handleGenericException(new RuntimeException("secret")).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("6. POST /prepare with SCHEDULED returns 202 Accepted and BUILDING availability")
    void prepareChapterPlaybackScheduledReturnsAcceptedBuilding() {
        when(preparePublicChapterPlaybackUseCase.execute(any(PreparePublicChapterNarrationPlaybackCommand.class)))
                .thenReturn(new PreparePublicChapterNarrationPlaybackResult(
                        CHAPTER_ID,
                        VOICE_KEY,
                        ReaderChapterNarrationPreparationDispatchStatus.SCHEDULED
                ));

        ResponseEntity<PublicChapterNarrationPrepareResponseDTO> response = controller.prepareChapterPlayback(
                CHAPTER_ID,
                new PrepareChapterNarrationPlaybackRequest(VOICE_KEY)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().chapterId()).isEqualTo(CHAPTER_ID);
        assertThat(response.getBody().voiceKey()).isEqualTo(VOICE_KEY);
        assertThat(response.getBody().availability()).isEqualTo(PublicChapterNarrationPlaybackAvailability.BUILDING);
    }

    @Test
    @DisplayName("7. POST /prepare with ALREADY_IN_FLIGHT returns 202 Accepted and BUILDING availability")
    void prepareChapterPlaybackAlreadyInFlightReturnsAcceptedBuilding() {
        when(preparePublicChapterPlaybackUseCase.execute(any(PreparePublicChapterNarrationPlaybackCommand.class)))
                .thenReturn(new PreparePublicChapterNarrationPlaybackResult(
                        CHAPTER_ID,
                        VOICE_KEY,
                        ReaderChapterNarrationPreparationDispatchStatus.ALREADY_IN_FLIGHT
                ));

        ResponseEntity<PublicChapterNarrationPrepareResponseDTO> response = controller.prepareChapterPlayback(
                CHAPTER_ID,
                new PrepareChapterNarrationPlaybackRequest(VOICE_KEY)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().chapterId()).isEqualTo(CHAPTER_ID);
        assertThat(response.getBody().voiceKey()).isEqualTo(VOICE_KEY);
        assertThat(response.getBody().availability()).isEqualTo(PublicChapterNarrationPlaybackAvailability.BUILDING);
    }

    @Test
    @DisplayName("8. POST /prepare with REJECTED returns 503 Service Unavailable")
    void prepareChapterPlaybackRejectedReturnsServiceUnavailable() {
        when(preparePublicChapterPlaybackUseCase.execute(any(PreparePublicChapterNarrationPlaybackCommand.class)))
                .thenReturn(new PreparePublicChapterNarrationPlaybackResult(
                        CHAPTER_ID,
                        VOICE_KEY,
                        ReaderChapterNarrationPreparationDispatchStatus.REJECTED
                ));

        ResponseEntity<PublicChapterNarrationPrepareResponseDTO> response = controller.prepareChapterPlayback(
                CHAPTER_ID,
                new PrepareChapterNarrationPlaybackRequest(VOICE_KEY)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNull();
    }

    @Test
    @DisplayName("9. POST /prepare with null or blank voiceKey returns 400 Bad Request")
    void prepareChapterPlaybackInvalidRequestReturnsBadRequest() {
        ResponseEntity<PublicChapterNarrationPrepareResponseDTO> response1 = controller.prepareChapterPlayback(
                CHAPTER_ID,
                null
        );
        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<PublicChapterNarrationPrepareResponseDTO> response2 = controller.prepareChapterPlayback(
                CHAPTER_ID,
                new PrepareChapterNarrationPlaybackRequest("   ")
        );
        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<PublicChapterNarrationPrepareResponseDTO> response3 = controller.prepareChapterPlayback(
                null,
                new PrepareChapterNarrationPlaybackRequest(VOICE_KEY)
        );
        assertThat(response3.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
