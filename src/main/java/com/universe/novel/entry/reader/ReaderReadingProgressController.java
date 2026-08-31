package com.universe.novel.entry.reader;

import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.reader.RecordReadingProgressCommand;
import com.universe.novel.application.reader.RecordReadingProgressUseCase;
import com.universe.shared.security.AuthenticatedEmailResolver;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
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

    private final AuthenticatedEmailResolver
            authenticatedEmailResolver;

    private final UserIdentityContract
            userIdentityContract;

    public ReaderReadingProgressController(
            RecordReadingProgressUseCase recordReadingProgressUseCase,
            AuthenticatedEmailResolver authenticatedEmailResolver,
            UserIdentityContract userIdentityContract
    ) {
        this.recordReadingProgressUseCase = Objects.requireNonNull(
                recordReadingProgressUseCase,
                "RecordReadingProgressUseCase không được để trống."
        );
        this.authenticatedEmailResolver = Objects.requireNonNull(
                authenticatedEmailResolver,
                "AuthenticatedEmailResolver không được để trống."
        );
        this.userIdentityContract = Objects.requireNonNull(
                userIdentityContract,
                "UserIdentityContract không được để trống."
        );
    }

    @PostMapping("/chapters/{chapterId}/progress")
    public ResponseEntity<Void> recordProgress(
            @PathVariable UUID chapterId,
            Authentication authentication
    ) {
        if (chapterId == null) {
            return ResponseEntity.badRequest().build();
        }

        Optional<String> emailOpt =
                authenticatedEmailResolver.resolve(authentication);

        if (emailOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<UserDTO> userOpt =
                userIdentityContract.findByEmail(emailOpt.get());

        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            recordReadingProgressUseCase.execute(
                    new RecordReadingProgressCommand(
                            userOpt.get().id(),
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
