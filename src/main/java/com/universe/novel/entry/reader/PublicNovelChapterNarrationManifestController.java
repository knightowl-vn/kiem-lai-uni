package com.universe.novel.entry.reader;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ManagedVoiceInvalidStateException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.narration.GetPublicChapterNarrationManifestQuery;
import com.universe.novel.application.narration.GetPublicChapterNarrationManifestUseCase;
import com.universe.novel.contracts.dto.narration.PublicChapterNarrationManifestDTO;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/**
 * Public REST controller exposing chapter narration manifest for browser playback (MS-04.9H.7A).
 */
@RestController
@RequestMapping("/api/novel/chapters/{chapterId}/narration")
public class PublicNovelChapterNarrationManifestController {

    private final GetPublicChapterNarrationManifestUseCase getManifestUseCase;

    public PublicNovelChapterNarrationManifestController(GetPublicChapterNarrationManifestUseCase getManifestUseCase) {
        this.getManifestUseCase = Objects.requireNonNull(getManifestUseCase, "getManifestUseCase must not be null");
    }

    /**
     * GET /api/novel/chapters/{chapterId}/narration/manifest?voiceKey={voiceKey}
     */
    @GetMapping("/manifest")
    public ResponseEntity<PublicChapterNarrationManifestDTO> getManifest(
            @PathVariable UUID chapterId,
            @RequestParam(name = "voiceKey", required = false) String voiceKey
    ) {
        PublicChapterNarrationManifestDTO manifest = getManifestUseCase.execute(
                new GetPublicChapterNarrationManifestQuery(chapterId, voiceKey)
        );
        return ResponseEntity.ok(manifest);
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
}
