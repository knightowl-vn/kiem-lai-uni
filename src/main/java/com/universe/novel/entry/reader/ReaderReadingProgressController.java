package com.universe.novel.entry.reader;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityAccessor;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.reader.RecordReadingProgressCommand;
import com.universe.novel.application.reader.RecordReadingProgressUseCase;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/novel")
public class ReaderReadingProgressController {

    private final RecordReadingProgressUseCase
            recordReadingProgressUseCase;

    public ReaderReadingProgressController(
            RecordReadingProgressUseCase recordReadingProgressUseCase
    ) {
        this.recordReadingProgressUseCase = Objects.requireNonNull(
                recordReadingProgressUseCase,
                "RecordReadingProgressUseCase không được để trống."
        );
    }

    @PostMapping("/chapters/{chapterId}/progress")
    public ResponseEntity<Void> recordProgress(
            @PathVariable UUID chapterId,
            HttpServletRequest request
    ) {
        if (chapterId == null) {
            return ResponseEntity.badRequest().build();
        }

        Optional<AuthenticatedRequestIdentity> identityOptional =
                AuthenticatedRequestIdentityAccessor.find(request);

        if (identityOptional.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            recordReadingProgressUseCase.execute(
                    new RecordReadingProgressCommand(
                            identityOptional.get().userId(),
                            chapterId
                    )
            );
            return ResponseEntity.noContent().build();
        } catch (ChapterNotFoundException exception) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (Exception exception) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
