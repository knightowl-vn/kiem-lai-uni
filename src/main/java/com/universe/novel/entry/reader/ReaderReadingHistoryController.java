package com.universe.novel.entry.reader;

import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.reader.ListUserReadingHistoryUseCase;
import com.universe.novel.application.reader.RecordReadingHistoryCommand;
import com.universe.novel.application.reader.RecordReadingHistoryUseCase;
import com.universe.novel.contracts.dto.reader.ReaderReadingHistoryDTO;
import com.universe.shared.security.AuthenticatedEmailResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Controller
@RequestMapping("/novel")
public class ReaderReadingHistoryController {

    private final RecordReadingHistoryUseCase recordReadingHistoryUseCase;
    private final ListUserReadingHistoryUseCase listUserReadingHistoryUseCase;
    private final AuthenticatedEmailResolver authenticatedEmailResolver;
    private final UserIdentityContract userIdentityContract;

    public ReaderReadingHistoryController(
            RecordReadingHistoryUseCase recordReadingHistoryUseCase,
            ListUserReadingHistoryUseCase listUserReadingHistoryUseCase,
            AuthenticatedEmailResolver authenticatedEmailResolver,
            UserIdentityContract userIdentityContract
    ) {
        this.recordReadingHistoryUseCase = Objects.requireNonNull(
                recordReadingHistoryUseCase,
                "RecordReadingHistoryUseCase không được để trống."
        );
        this.listUserReadingHistoryUseCase = Objects.requireNonNull(
                listUserReadingHistoryUseCase,
                "ListUserReadingHistoryUseCase không được để trống."
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

    @PostMapping("/chapters/{chapterId}/history")
    @ResponseBody
    public ResponseEntity<Void> recordHistory(
            @PathVariable UUID chapterId,
            Authentication authentication
    ) {
        if (chapterId == null) {
            return ResponseEntity.badRequest().build();
        }

        Optional<UserDTO> userOpt = resolveUser(authentication);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            recordReadingHistoryUseCase.execute(
                    new RecordReadingHistoryCommand(
                            userOpt.get().id(),
                            chapterId
                    )
            );
            return ResponseEntity.noContent().build();
        } catch (ChapterNotFoundException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @GetMapping("/history")
    public String historyPage(
            Authentication authentication,
            Model model
    ) {
        Optional<UserDTO> userOpt = resolveUser(authentication);
        if (userOpt.isEmpty()) {
            return "redirect:/login";
        }

        List<ReaderReadingHistoryDTO> history =
                listUserReadingHistoryUseCase.execute(userOpt.get().id());

        model.addAttribute("historyList", history);
        model.addAttribute("pageTitle", "Lịch sử đọc");

        return "novel/reader/history";
    }

    private Optional<UserDTO> resolveUser(Authentication authentication) {
        return authenticatedEmailResolver.resolve(authentication)
                .flatMap(userIdentityContract::findByEmail);
    }
}
