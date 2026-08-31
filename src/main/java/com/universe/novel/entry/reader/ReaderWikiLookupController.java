package com.universe.novel.entry.reader;

import com.universe.novel.application.reader.ChapterWikiReferenceResolutionSource;
import com.universe.novel.application.reader.LookupContextualWikiUseCase;
import com.universe.novel.application.reader.ReaderChapterWikiResolutionResult;
import com.universe.novel.application.reader.ReaderWikiLookupResult;
import com.universe.novel.application.reader.ResolveReaderChapterWikiQuery;
import com.universe.novel.application.reader.ResolveReaderChapterWikiUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/**
 * REST controller cung cấp endpoint tra cứu thông tin Wiki theo ngữ cảnh đọc truyện.
 */
@RestController
@RequestMapping("/novel/api/wiki")
public class ReaderWikiLookupController {

    private final LookupContextualWikiUseCase lookupContextualWikiUseCase;
    private final ResolveReaderChapterWikiUseCase resolveReaderChapterWikiUseCase;

    public ReaderWikiLookupController(
            LookupContextualWikiUseCase lookupContextualWikiUseCase,
            ResolveReaderChapterWikiUseCase resolveReaderChapterWikiUseCase
    ) {
        this.lookupContextualWikiUseCase = Objects.requireNonNull(
                lookupContextualWikiUseCase,
                "LookupContextualWikiUseCase không được để trống."
        );
        this.resolveReaderChapterWikiUseCase = Objects.requireNonNull(
                resolveReaderChapterWikiUseCase,
                "ResolveReaderChapterWikiUseCase không được để trống."
        );
    }

    /**
     * Tra cứu bài viết Wiki đã xuất bản phù hợp với văn bản được chọn.
     * Hỗ trợ phân giải theo thứ tự ưu tiên:
     * 1. ACTIVE occurrence-specific binding (khi có chapterId và occurrence >= 1)
     * 2. chapter-wide binding (khi có chapterId)
     * 3. global Wiki contextual lookup (khi không có chapterId hoặc fallback)
     *
     * GET /novel/api/wiki/lookup?q={selectedText}&chapterId={chapterId}&occurrence={occurrenceIndex}
     *
     * @param query chuỗi văn bản cần tra cứu
     * @param chapterId ID chương truyện (tùy chọn)
     * @param occurrence vị trí xuất hiện của từ khóa trong chương (>= 1, tùy chọn)
     * @return 200 OK kèm payload JSON kết quả tra cứu
     */
    @GetMapping("/lookup")
    public ResponseEntity<ReaderChapterWikiResolutionResult> lookup(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "chapterId", required = false) UUID chapterId,
            @RequestParam(name = "occurrence", required = false) Integer occurrence
    ) {
        if (chapterId != null) {
            Integer sanitizedOccurrence = (occurrence != null && occurrence >= 1) ? occurrence : null;
            ResolveReaderChapterWikiQuery resolutionQuery = new ResolveReaderChapterWikiQuery(
                    chapterId,
                    query,
                    sanitizedOccurrence
            );
            ReaderChapterWikiResolutionResult result = resolveReaderChapterWikiUseCase.execute(resolutionQuery);
            return ResponseEntity.ok(result);
        }

        ReaderWikiLookupResult globalResult = lookupContextualWikiUseCase.execute(query);
        ReaderChapterWikiResolutionResult response = new ReaderChapterWikiResolutionResult(
                globalResult.query(),
                ChapterWikiReferenceResolutionSource.GLOBAL_LOOKUP,
                globalResult.hasExactMatch(),
                globalResult.items(),
                null
        );
        return ResponseEntity.ok(response);
    }
}