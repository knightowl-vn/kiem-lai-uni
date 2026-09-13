package com.universe.novel.entry.reader;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ManagedVoiceInvalidStateException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.narration.GetPublicChapterNarrationPlaybackQuery;
import com.universe.novel.application.narration.GetPublicChapterNarrationPlaybackUseCase;
import com.universe.novel.application.narration.PreparePublicChapterNarrationPlaybackCommand;
import com.universe.novel.application.narration.PreparePublicChapterNarrationPlaybackResult;
import com.universe.novel.application.narration.PreparePublicChapterNarrationPlaybackUseCase;
import com.universe.novel.application.narration.ReaderChapterNarrationPreparationDispatchStatus;
import com.universe.novel.contracts.dto.narration.PrepareChapterNarrationPlaybackRequest;
import com.universe.novel.contracts.dto.narration.PublicChapterNarrationPlaybackAvailability;
import com.universe.novel.contracts.dto.narration.PublicChapterNarrationPlaybackDTO;
import com.universe.novel.contracts.dto.narration.PublicChapterNarrationPrepareResponseDTO;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/**
 * Public REST controller for passive chapter playback metadata and chapter-level narration preparation.
 */
@RestController
@RequestMapping("/api/novel/chapters/{chapterId}/narration")
public class PublicNovelChapterNarrationPlaybackController {

    private static final Logger log = LoggerFactory.getLogger(PublicNovelChapterNarrationPlaybackController.class);

    private final GetPublicChapterNarrationPlaybackUseCase getPublicPlaybackUseCase;
    private final PreparePublicChapterNarrationPlaybackUseCase preparePublicChapterPlaybackUseCase;

    public PublicNovelChapterNarrationPlaybackController(
            GetPublicChapterNarrationPlaybackUseCase getPublicPlaybackUseCase,
            PreparePublicChapterNarrationPlaybackUseCase preparePublicChapterPlaybackUseCase
    ) {
        this.getPublicPlaybackUseCase = Objects.requireNonNull(
                getPublicPlaybackUseCase, "getPublicPlaybackUseCase must not be null"
        );
        this.preparePublicChapterPlaybackUseCase = Objects.requireNonNull(
                preparePublicChapterPlaybackUseCase, "preparePublicChapterPlaybackUseCase must not be null"
        );
    }

    /**
     * POST /api/novel/chapters/{chapterId}/narration/prepare
     *
     * @param chapterId   identity of the published chapter
     * @param requestBody JSON payload containing public voiceKey
     * @return 202 Accepted with BUILDING availability, or 503 Service Unavailable on executor rejection
     */
    @PostMapping("/prepare")
    public ResponseEntity<PublicChapterNarrationPrepareResponseDTO> prepareChapterPlayback(
            @PathVariable UUID chapterId,
            @RequestBody(required = false) PrepareChapterNarrationPlaybackRequest requestBody
    ) {
        if (chapterId == null || requestBody == null || requestBody.voiceKey() == null || requestBody.voiceKey().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        PreparePublicChapterNarrationPlaybackResult result = preparePublicChapterPlaybackUseCase.execute(
                new PreparePublicChapterNarrationPlaybackCommand(
                        chapterId,
                        requestBody.voiceKey()
                )
        );

        if (result.dispatchStatus() == ReaderChapterNarrationPreparationDispatchStatus.REJECTED) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
                new PublicChapterNarrationPrepareResponseDTO(
                        result.chapterId(),
                        result.voiceKey(),
                        PublicChapterNarrationPlaybackAvailability.BUILDING
                )
        );
    }

    /**
     * GET /api/novel/chapters/{chapterId}/narration/playback?voiceKey={voiceKey}
     */
    @GetMapping("/playback")
    public ResponseEntity<PublicChapterNarrationPlaybackDTO> getChapterPlayback(
            @PathVariable UUID chapterId,
            @RequestParam(name = "voiceKey", required = false) String voiceKey,
            HttpServletResponse response
    ) {
        disableCaching(response);
        PublicChapterNarrationPlaybackDTO playback = getPublicPlaybackUseCase.execute(
                new GetPublicChapterNarrationPlaybackQuery(chapterId, voiceKey)
        );
        return ResponseEntity.ok(playback);
    }

    @ExceptionHandler(ChapterNotFoundException.class)
    public ResponseEntity<Void> handleChapterNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler(ManagedVoiceNotFoundException.class)
    public ResponseEntity<Void> handleVoiceNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler(ManagedVoiceInvalidStateException.class)
    public ResponseEntity<Void> handleVoiceInvalidState() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Void> handleIllegalArgument() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Void> handleIllegalState() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Void> handleGenericException(Exception ex) {
        log.error("Unexpected error during reader narration playback request", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    private void disableCaching(HttpServletResponse response) {
        if (response != null) {
            response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            response.setHeader("Pragma", "no-cache");
            response.setDateHeader("Expires", 0);
        }
    }
}
