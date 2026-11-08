package com.universe.screen.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.Collection;
import java.util.UUID;

/**
 * Thrown when an operation on a ScreenTitle video set fails optimistic concurrency / dynamic asset set checks.
 */
public class ScreenTitleVideoConflictException extends BaseApplicationException {

    public ScreenTitleVideoConflictException(
            UUID titleId,
            Collection<?> preliminaryBindings,
            Collection<?> authoritativeBindings
    ) {
        super("SCREEN_TITLE_VIDEO_CONFLICT",
                "Xung đột danh sách video trên ScreenTitle [" + titleId + "]: Danh sách video hiện tại "
                        + authoritativeBindings + " không khớp với danh sách sơ bộ " + preliminaryBindings
                        + ". Thao tác đã bị hủy để tránh xung đột khóa hoặc sai lệch hiển thị video.");
    }
}
