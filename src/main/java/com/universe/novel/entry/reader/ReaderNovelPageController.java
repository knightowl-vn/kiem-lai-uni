package com.universe.novel.entry.reader;

import com.universe.novel.application.reader.GetReaderNovelLandingUseCase;
import com.universe.novel.contracts.dto.reader.ReaderNovelLandingDTO;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/novel")
public class ReaderNovelPageController {

    private final GetReaderNovelLandingUseCase
            getReaderNovelLandingUseCase;

    public ReaderNovelPageController(
            GetReaderNovelLandingUseCase getReaderNovelLandingUseCase
    ) {
        this.getReaderNovelLandingUseCase =
                getReaderNovelLandingUseCase;
    }

    @GetMapping
    public String landingPage(
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

        return "novel/index";
    }
}