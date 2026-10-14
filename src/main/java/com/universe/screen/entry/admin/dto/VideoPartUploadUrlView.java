package com.universe.screen.entry.admin.dto;

/**
 * View DTO containing an authorized presigned PUT upload URL for an individual part.
 */
public record VideoPartUploadUrlView(
        int partNumber,
        String uploadUrl
) {
}
