package com.universe.novel.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

public class ChapterCannotBeDeletedException
        extends BaseApplicationException {

    private static final long serialVersionUID =
            1L;

    public ChapterCannotBeDeletedException(
            UUID chapterId
    ) {
        super(
                "NOVEL_CHAPTER_CANNOT_BE_DELETED",
                "Chương không thể xóa vĩnh viễn vì có lịch sử hoặc không thể chứng minh là bản nháp chưa từng xuất bản; hãy lưu trữ chương thay thế: "
                        + chapterId
        );
    }
}
