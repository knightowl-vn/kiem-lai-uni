package com.universe.novel.entry.reader;

import com.universe.novel.application.reader.GetReaderChapterDetailUseCase;
import com.universe.novel.contracts.dto.reader.ReaderChapterDetailDTO;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Objects;

@Controller
@RequestMapping("/novel")
public class ReaderChapterPageController {

    private final GetReaderChapterDetailUseCase
            getReaderChapterDetailUseCase;

    public ReaderChapterPageController(
            GetReaderChapterDetailUseCase getReaderChapterDetailUseCase
    ) {
        this.getReaderChapterDetailUseCase =
                Objects.requireNonNull(
                        getReaderChapterDetailUseCase,
                        "GetReaderChapterDetailUseCase không được để trống."
                );
    }

    @GetMapping("/chapters/{chapterSlug}")
    public String chapterPage(
            @PathVariable String chapterSlug,
            Model model
    ) {
        ReaderChapterDetailDTO chapter =
                getReaderChapterDetailUseCase.execute(
                        chapterSlug
                );

        model.addAttribute(
                "chapter",
                chapter
        );

        model.addAttribute(
                "pageTitle",
                "Chương "
                        + chapter.chapterNumber()
                        + ": "
                        + chapter.title()
        );

        return "novel/chapter";
    }
}
