package com.universe.novel.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

public class ChapterSortOrderAlreadyExistsException
        extends BaseApplicationException {

    private static final long serialVersionUID =
            1L;

    public ChapterSortOrderAlreadyExistsException(
            UUID volumeId,
            int sortOrder
    ) {
        super(
                "NOVEL_CHAPTER_SORT_ORDER_EXISTS",
                "Thứ tự chương "
                        + sortOrder
                        + " đã tồn tại trong tập "
                        + volumeId
                        + "."
        );
    }
}