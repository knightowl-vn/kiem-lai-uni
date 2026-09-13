package com.universe.novel.entry.reader;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityAccessor;
import com.universe.novel.application.reader.GetContinueReadingUseCase;
import com.universe.novel.application.reader.GetReaderNovelLandingUseCase;
import com.universe.novel.contracts.dto.reader.ReaderContinueReadingDTO;
import com.universe.novel.contracts.dto.reader.ReaderNovelLandingDTO;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Objects;
import java.util.Optional;

@Controller
@RequestMapping("/novel")
public class ReaderNovelPageController {

    private static final Logger log =
            LoggerFactory.getLogger(ReaderNovelPageController.class);

    private final GetReaderNovelLandingUseCase
            getReaderNovelLandingUseCase;

    private final GetContinueReadingUseCase
            getContinueReadingUseCase;

    public ReaderNovelPageController(
            GetReaderNovelLandingUseCase getReaderNovelLandingUseCase,
            GetContinueReadingUseCase getContinueReadingUseCase
    ) {
        this.getReaderNovelLandingUseCase = Objects.requireNonNull(
                getReaderNovelLandingUseCase,
                "GetReaderNovelLandingUseCase không được để trống."
        );
        this.getContinueReadingUseCase = Objects.requireNonNull(
                getContinueReadingUseCase,
                "GetContinueReadingUseCase không được để trống."
        );
    }

    @GetMapping
    public String landingPage(
            HttpServletRequest request,
            Model model
    ) {
        ReaderNovelLandingDTO landing =
                getReaderNovelLandingUseCase.execute();

        model.addAttribute(
                "novel",
                landing.novel()
        );

        model.addAttribute(
                "volumes",
                landing.volumes()
        );

        model.addAttribute(
                "firstChapter",
                landing.firstChapter()
        );

        model.addAttribute(
                "pageTitle",
                landing.novel().title()
        );

        Optional<AuthenticatedRequestIdentity> identityOptional =
                AuthenticatedRequestIdentityAccessor.find(request);

        if (identityOptional.isPresent()) {
            AuthenticatedRequestIdentity identity = identityOptional.get();
            try {
                Optional<ReaderContinueReadingDTO> continueReadingOpt =
                        getContinueReadingUseCase.execute(identity.userId());

                continueReadingOpt.ifPresent(continueReading ->
                        model.addAttribute(
                                "continueReading",
                                continueReading
                        )
                );
            } catch (Exception exception) {
                log.warn(
                        "Không thể tải thông tin Đọc tiếp cho người dùng [{}]: {}",
                        identity.normalizedEmail(),
                        exception.getMessage(),
                        exception
                );
            }
        }

        return "novel/index";
    }
}
