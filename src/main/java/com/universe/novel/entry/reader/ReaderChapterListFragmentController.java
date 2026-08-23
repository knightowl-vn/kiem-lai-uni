package com.universe.novel.entry.reader;

import com.universe.novel.application.reader.GetReaderChapterListUseCase;
import com.universe.novel.contracts.dto.reader.ReaderChapterListItemDTO;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;
import java.util.UUID;

@Controller
@RequestMapping("/novel/volumes")
public class ReaderChapterListFragmentController {

    private final GetReaderChapterListUseCase
            getReaderChapterListUseCase;

    public ReaderChapterListFragmentController(
            GetReaderChapterListUseCase getReaderChapterListUseCase
    ) {
        this.getReaderChapterListUseCase =
                getReaderChapterListUseCase;
    }

    @GetMapping("/{volumeId}/chapters")
    public String getChapterList(
            @PathVariable UUID volumeId,
            Model model
    ) {

        List<ReaderChapterListItemDTO> chapters =
                getReaderChapterListUseCase.execute(
                        volumeId
                );

        model.addAttribute(
                "chapters",
                chapters
        );

        return "novel/chapter-list :: chapterList";
    }
}