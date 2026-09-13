package com.universe.novel.entry.reader;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityAccessor;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.reader.ListUserReadingHistoryUseCase;
import com.universe.novel.application.reader.RecordReadingHistoryCommand;
import com.universe.novel.application.reader.RecordReadingHistoryUseCase;
import com.universe.novel.contracts.dto.reader.ReaderReadingHistoryDTO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    public ReaderReadingHistoryController(
            RecordReadingHistoryUseCase recordReadingHistoryUseCase,
            ListUserReadingHistoryUseCase listUserReadingHistoryUseCase
    ) {
        this.recordReadingHistoryUseCase = Objects.requireNonNull(
                recordReadingHistoryUseCase,
                "RecordReadingHistoryUseCase không được để trống."
        );
        this.listUserReadingHistoryUseCase = Objects.requireNonNull(
                listUserReadingHistoryUseCase,
                "ListUserReadingHistoryUseCase không được để trống."
        );
    }

    @PostMapping("/chapters/{chapterId}/history")
    @ResponseBody
    public ResponseEntity<Void> recordHistory(
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
            recordReadingHistoryUseCase.execute(
                    new RecordReadingHistoryCommand(
                            identityOptional.get().userId(),
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
            HttpServletRequest request,
            Model model
    ) {
        Optional<AuthenticatedRequestIdentity> identityOptional =
                AuthenticatedRequestIdentityAccessor.find(request);
        if (identityOptional.isEmpty()) {
            return "redirect:/login";
        }

        List<ReaderReadingHistoryDTO> history =
                listUserReadingHistoryUseCase.execute(
                        identityOptional.get().userId()
                );

        model.addAttribute("historyList", history);
        model.addAttribute("pageTitle", "Lịch sử đọc");

        return "novel/reader/history";
    }
}
