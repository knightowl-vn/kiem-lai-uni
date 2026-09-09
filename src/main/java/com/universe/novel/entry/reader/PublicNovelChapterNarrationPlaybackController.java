package com.universe.novel.entry.reader;

import com.universe.novel.application.exceptions.ChapterNarrationSegmentInvalidStateException;
import com.universe.novel.application.exceptions.ChapterNarrationSegmentNotFoundException;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ManagedVoiceInvalidStateException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.narration.GetPublicChapterNarrationPlaybackQuery;
import com.universe.novel.application.narration.GetPublicChapterNarrationPlaybackUseCase;
import com.universe.novel.application.narration.PreparePublicReaderNarrationPlaybackCommand;
import com.universe.novel.application.narration.PreparePublicReaderNarrationPlaybackResult;
import com.universe.novel.application.narration.PreparePublicReaderNarrationPlaybackUseCase;
import com.universe.novel.contracts.dto.narration.PrepareReaderNarrationPlaybackRequest;
import com.universe.novel.contracts.dto.narration.PublicChapterNarrationPlaybackDTO;
import com.universe.novel.contracts.dto.narration.PublicReaderNarrationPlaybackDTO;
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
 * Public REST controller for passive chapter playback metadata and legacy on-demand segment preparation.
 */
@RestController
@RequestMapping("/api/novel/chapters/{chapterId}/narration")
public class PublicNovelChapterNarrationPlaybackController {

    private static final Logger log = LoggerFactory.getLogger(PublicNovelChapterNarrationPlaybackController.class);

    private final PreparePublicReaderNarrationPlaybackUseCase preparePublicPlaybackUseCase;
    private final GetPublicChapterNarrationPlaybackUseCase getPublicPlaybackUseCase;

    public PublicNovelChapterNarrationPlaybackController(
            PreparePublicReaderNarrationPlaybackUseCase preparePublicPlaybackUseCase,
            GetPublicChapterNarrationPlaybackUseCase getPublicPlaybackUseCase
    ) {
        this.preparePublicPlaybackUseCase = Objects.requireNonNull(
                preparePublicPlaybackUseCase, "preparePublicPlaybackUseCase must not be null"
        );
        this.getPublicPlaybackUseCase = Objects.requireNonNull(
                getPublicPlaybackUseCase, "getPublicPlaybackUseCase must not be null"
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

    /**
     * POST /api/novel/chapters/{chapterId}/narration/segments/{segmentId}/prepare
     *
     * @param chapterId   identity of the published chapter
     * @param segmentId   identity of the requested segment
     * @param requestBody JSON payload containing public voiceKey
     * @return playback preparation status and safe audio streaming URL
     */
    @PostMapping("/segments/{segmentId}/prepare")
    public ResponseEntity<PublicReaderNarrationPlaybackDTO> prepareSegmentPlayback(
            @PathVariable UUID chapterId,
            @PathVariable UUID segmentId,
            @RequestBody(required = false) PrepareReaderNarrationPlaybackRequest requestBody
    ) {
        if (chapterId == null || segmentId == null || requestBody == null || requestBody.voiceKey() == null || requestBody.voiceKey().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        PreparePublicReaderNarrationPlaybackResult result = preparePublicPlaybackUseCase.execute(
                new PreparePublicReaderNarrationPlaybackCommand(
                        chapterId,
                        segmentId,
                        requestBody.voiceKey()
                )
        );

        PublicReaderNarrationPlaybackDTO responseDto = PublicReaderNarrationPlaybackDTO.from(result);
        return ResponseEntity.ok(responseDto);
    }

    @ExceptionHandler(ChapterNotFoundException.class)
    public ResponseEntity<Void> handleChapterNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler(ManagedVoiceNotFoundException.class)
    public ResponseEntity<Void> handleVoiceNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler(ChapterNarrationSegmentNotFoundException.class)
    public ResponseEntity<Void> handleSegmentNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler(ManagedVoiceInvalidStateException.class)
    public ResponseEntity<Void> handleVoiceInvalidState() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }

    @ExceptionHandler(ChapterNarrationSegmentInvalidStateException.class)
    public ResponseEntity<Void> handleSegmentInvalidState() {
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
