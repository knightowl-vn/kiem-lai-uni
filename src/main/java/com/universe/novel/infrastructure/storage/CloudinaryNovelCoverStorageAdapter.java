package com.universe.novel.infrastructure.storage;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.universe.novel.application.ports.NovelCoverStoragePort;
import com.universe.novel.application.profile.NovelCoverUpload;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Component
public class CloudinaryNovelCoverStorageAdapter
        implements NovelCoverStoragePort {

    private static final long MAX_FILE_SIZE =
            5L * 1024 * 1024; // 5 MB

    private static final String BASE_FOLDER =
            "kiemlai/novel/covers";

    private static final String OUTPUT_FORMAT =
            "webp";

    private static final Set<String> SUPPORTED_CONTENT_TYPES =
            Set.of(
                    "image/jpeg",
                    "image/png",
                    "image/webp"
            );

    private static final Set<String> SUPPORTED_EXTENSIONS =
            Set.of(
                    "jpg",
                    "jpeg",
                    "png",
                    "webp"
            );

    private final Cloudinary cloudinary;

    public CloudinaryNovelCoverStorageAdapter(
            Cloudinary cloudinary
    ) {
        this.cloudinary =
                Objects.requireNonNull(
                        cloudinary,
                        "Cloudinary không được để trống."
                );
    }

    @Override
    public String upload(
            String novelSlug,
            NovelCoverUpload coverUpload
    ) {
        validateSlug(novelSlug);
        validateCoverUpload(coverUpload);

        String normalizedSlug =
                novelSlug.trim().toLowerCase(Locale.ROOT);

        String folder =
                BASE_FOLDER
                        + "/"
                        + normalizedSlug;

        String publicId =
                folder
                        + "/"
                        + UUID.randomUUID();

        try {
            Map<?, ?> uploadResult =
                    cloudinary
                            .uploader()
                            .upload(
                                    coverUpload.content(),
                                    ObjectUtils.asMap(
                                            "asset_folder", folder,
                                            "public_id", publicId,
                                            "overwrite", false,
                                            "resource_type", "image",
                                            "format", OUTPUT_FORMAT
                                    )
                            );

            Object secureUrl =
                    uploadResult.get("secure_url");

            if (secureUrl == null
                    || secureUrl.toString().isBlank()) {
                throw new IllegalStateException(
                        "Cloudinary không trả về secure_url khi tải ảnh bìa Novel."
                );
            }

            return secureUrl.toString();

        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Không thể tải ảnh bìa Novel lên Cloudinary.",
                    exception
            );
        }
    }

    private void validateSlug(
            String novelSlug
    ) {
        if (novelSlug == null
                || novelSlug.isBlank()) {
            throw new IllegalArgumentException(
                    "Slug của Novel không được để trống."
            );
        }
    }

    private void validateCoverUpload(
            NovelCoverUpload coverUpload
    ) {
        if (coverUpload == null) {
            throw new IllegalArgumentException(
                    "Dữ liệu ảnh bìa không được để trống."
            );
        }

        byte[] content = coverUpload.content();
        if (content == null
                || content.length == 0) {
            throw new IllegalArgumentException(
                    "Vui lòng chọn file ảnh bìa hợp lệ."
            );
        }

        if (content.length > MAX_FILE_SIZE) {
            throw new IllegalArgumentException(
                    "Ảnh bìa không được vượt quá 5 MB."
            );
        }

        String contentType = coverUpload.contentType();
        if (contentType == null
                || !SUPPORTED_CONTENT_TYPES.contains(
                        contentType.trim().toLowerCase(Locale.ROOT)
                )) {
            throw new IllegalArgumentException(
                    "Chỉ chấp nhận ảnh định dạng JPG, PNG hoặc WEBP."
            );
        }

        String filename = coverUpload.originalFilename();
        if (filename == null
                || filename.isBlank()) {
            throw new IllegalArgumentException(
                    "Tên file ảnh bìa không hợp lệ."
            );
        }

        String extension = extractExtension(filename);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException(
                    "Chỉ chấp nhận ảnh định dạng JPG, PNG hoặc WEBP."
            );
        }
    }

    private String extractExtension(
            String filename
    ) {
        String normalized = filename.trim().replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < normalized.length() - 1) {
            normalized = normalized.substring(lastSlash + 1);
        }

        int dotIndex = normalized.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == normalized.length() - 1) {
            return "";
        }

        return normalized.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }
}
