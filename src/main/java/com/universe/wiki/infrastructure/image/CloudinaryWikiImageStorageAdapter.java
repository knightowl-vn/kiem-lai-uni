package com.universe.wiki.infrastructure.image;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;

import com.universe.wiki.application.ports
        .LegacyWikiImageStoragePort;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

@Component
public class CloudinaryWikiImageStorageAdapter
        implements LegacyWikiImageStoragePort {

    private final Cloudinary cloudinary;

    public CloudinaryWikiImageStorageAdapter(
            Cloudinary cloudinary
    ) {
        this.cloudinary =
                Objects.requireNonNull(
                        cloudinary,
                        "Cloudinary không được để trống."
                );
    }
    
    @Override
    public void delete(
            String publicId
    ) {
        if (
                publicId == null
                || publicId.isBlank()
        ) {
            throw new IllegalArgumentException(
                    "Cloudinary public ID "
                            + "không được để trống."
            );
        }

        try {
            Map<?, ?> destroyResult =
                    cloudinary
                            .uploader()
                            .destroy(
                                    publicId.trim(),
                                    ObjectUtils.asMap(
                                            "resource_type",
                                            "image",

                                            "type",
                                            "upload",

                                            /*
                                             * Xóa cache CDN của asset
                                             * sau khi destroy.
                                             */
                                            "invalidate",
                                            true
                                    )
                            );

            String result =
                    requireResultValue(
                            destroyResult,
                            "result",
                            "Cloudinary không trả về "
                                    + "kết quả xóa ảnh."
                    );

            /*
             * "ok":
             * asset vừa được xóa.
             *
             * "not found":
             * asset không còn trên Cloudinary.
             * Với cleanup idempotent, trạng thái này
             * cũng được xem là đã đạt mục tiêu.
             */
            if (
                    !"ok".equalsIgnoreCase(
                            result
                    )
                    && !"not found"
                            .equalsIgnoreCase(
                                    result
                            )
            ) {
                throw new IllegalStateException(
                        "Cloudinary không thể xóa ảnh Wiki. "
                                + "Kết quả: "
                                + result
                );
            }

        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Không thể xóa ảnh Wiki "
                            + "khỏi Cloudinary.",
                    exception
            );
        }
    }

    /*
     * =====================================================
     * CLOUDINARY RESULT VALIDATION
     * =====================================================
     */

    private String requireResultValue(
            Map<?, ?> uploadResult,
            String key,
            String errorMessage
    ) {
        Object value =
                uploadResult.get(
                        key
                );

        if (
                value == null
                || value.toString().isBlank()
        ) {
            throw new IllegalStateException(
                    errorMessage
            );
        }

        return value.toString();
    }
}