package com.universe.wiki.infrastructure.image;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;

import com.universe.wiki.application.image
        .WikiImageStoragePort;
import com.universe.wiki.application.image
        .WikiImageUploadResult;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Component
public class CloudinaryWikiImageStorageAdapter
        implements WikiImageStoragePort {

    private static final String WIKI_FOLDER =
            "kiemlai/wiki";

    private static final String OUTPUT_FORMAT =
            "webp";

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
    public WikiImageUploadResult upload(
            String originalFilename,
            String contentType,
            byte[] content
    ) {
        try {
            String imageId =
                    UUID.randomUUID()
                            .toString();

            Map<?, ?> uploadResult =
                    cloudinary
                            .uploader()
                            .upload(
                                    content,
                                    ObjectUtils.asMap(

                                            /*
                                             * Thư mục dành riêng
                                             * cho media của Wiki.
                                             */
                                            "asset_folder",
                                            WIKI_FOLDER,

                                            /*
                                             * Mỗi ảnh Wiki có một
                                             * public ID riêng.
                                             *
                                             * Không dùng articleId
                                             * vì một bài có thể chứa
                                             * nhiều ảnh.
                                             */
                                            "public_id",
                                            imageId,

                                            /*
                                             * UUID là duy nhất,
                                             * không cho ghi đè asset cũ.
                                             */
                                            "overwrite",
                                            false,

                                            /*
                                             * Đây là resource hình ảnh.
                                             */
                                            "resource_type",
                                            "image",

                                            /*
                                             * Chuẩn hóa mọi ảnh Wiki
                                             * thành WebP.
                                             *
                                             * JPG / PNG / WEBP đầu vào
                                             * → WEBP đầu ra.
                                             */
                                            "format",
                                            OUTPUT_FORMAT
                                    )
                            );

            String secureUrl =
                    requireResultValue(
                            uploadResult,
                            "secure_url",
                            "Cloudinary không trả về secure_url."
                    );

            String publicId =
                    requireResultValue(
                            uploadResult,
                            "public_id",
                            "Cloudinary không trả về public_id."
                    );

            return new WikiImageUploadResult(
                    secureUrl,
                    publicId
            );

        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Không thể tải ảnh Wiki lên Cloudinary.",
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