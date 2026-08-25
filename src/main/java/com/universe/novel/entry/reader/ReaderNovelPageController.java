package com.universe.novel.entry.reader;

import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.novel.application.reader.GetContinueReadingUseCase;
import com.universe.novel.application.reader.GetReaderNovelLandingUseCase;
import com.universe.novel.contracts.dto.reader.ReaderContinueReadingDTO;
import com.universe.novel.contracts.dto.reader.ReaderNovelLandingDTO;
import com.universe.shared.security.AuthenticatedEmailResolver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
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

    private final AuthenticatedEmailResolver
            authenticatedEmailResolver;

    private final UserIdentityContract
            userIdentityContract;

    public ReaderNovelPageController(
            GetReaderNovelLandingUseCase getReaderNovelLandingUseCase,
            GetContinueReadingUseCase getContinueReadingUseCase,
            AuthenticatedEmailResolver authenticatedEmailResolver,
            UserIdentityContract userIdentityContract
    ) {
        this.getReaderNovelLandingUseCase = Objects.requireNonNull(
                getReaderNovelLandingUseCase,
                "GetReaderNovelLandingUseCase không được để trống."
        );
        this.getContinueReadingUseCase = Objects.requireNonNull(
                getContinueReadingUseCase,
                "GetContinueReadingUseCase không được để trống."
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

    @GetMapping
    public String landingPage(
            Authentication authentication,
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
                "pageTitle",
                landing.novel().title()
        );

        Optional<String> emailOpt =
                authenticatedEmailResolver.resolve(authentication);

        if (emailOpt.isPresent()) {
            try {
                Optional<UserDTO> userOpt =
                        userIdentityContract.findByEmail(emailOpt.get());

                if (userOpt.isPresent()) {
                    Optional<ReaderContinueReadingDTO> continueReadingOpt =
                            getContinueReadingUseCase.execute(userOpt.get().id());

                    continueReadingOpt.ifPresent(continueReading ->
                            model.addAttribute(
                                    "continueReading",
                                    continueReading
                            )
                    );
                }
            } catch (Exception exception) {
                log.warn(
                        "Không thể tải thông tin Đọc tiếp cho người dùng [{}]: {}",
                        emailOpt.get(),
                        exception.getMessage(),
                        exception
                );
            }
        }

        return "novel/index";
    }
}