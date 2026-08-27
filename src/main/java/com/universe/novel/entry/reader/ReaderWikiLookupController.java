package com.universe.novel.entry.reader;

import com.universe.novel.application.reader.LookupContextualWikiUseCase;
import com.universe.novel.application.reader.ReaderWikiLookupResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * REST controller cung cấp endpoint tra cứu thông tin Wiki theo ngữ cảnh đọc truyện.
 */
@RestController
@RequestMapping("/novel/api/wiki")
public class ReaderWikiLookupController {

    private final LookupContextualWikiUseCase lookupContextualWikiUseCase;

    public ReaderWikiLookupController(LookupContextualWikiUseCase lookupContextualWikiUseCase) {
        this.lookupContextualWikiUseCase = Objects.requireNonNull(
                lookupContextualWikiUseCase,
                "LookupContextualWikiUseCase không được để trống."
        );
    }

    /**
     * Tra cứu bài viết Wiki đã xuất bản phù hợp với văn bản được chọn.
     *
     * GET /novel/api/wiki/lookup?q={selectedText}
     *
     * @param query chuỗi văn bản cần tra cứu
     * @return 200 OK kèm payload JSON kết quả tra cứu
     */
    @GetMapping("/lookup")
    public ResponseEntity<ReaderWikiLookupResult> lookup(
            @RequestParam(name = "q", required = false) String query
    ) {
        ReaderWikiLookupResult result = lookupContextualWikiUseCase.execute(query);
        return ResponseEntity.ok(result);
    }
}