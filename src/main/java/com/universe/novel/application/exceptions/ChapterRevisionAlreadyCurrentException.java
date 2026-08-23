package com.universe.novel.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

public class ChapterRevisionAlreadyCurrentException
        extends BaseApplicationException {

    private static final long serialVersionUID =
            1L;

    public ChapterRevisionAlreadyCurrentException(
            UUID chapterId,
            long revisionNumber
    ) {
        super(
                "NOVEL_CHAPTER_REVISION_ALREADY_CURRENT",
                "Nội dung biên tập (tiêu đề, tóm tắt, nội dung) của chương "
                        + chapterId
                        + " đã hoàn toàn trùng khớp với phiên bản #"
                        + revisionNumber
                        + "; không cần khôi phục."
        );
    }
}
