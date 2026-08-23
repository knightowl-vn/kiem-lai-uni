package com.universe.wiki.application.image;

import org.springframework.stereotype.Service;

import com.universe.wiki.application.ports.WikiImageStoragePort;
import com.universe.wiki.application.ports.WikiImageRepositoryPort;
import com.universe.wiki.application.ports.WikiImageStoragePort;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Service
public class UploadWikiImageUseCase {

    private static final long MAX_FILE_SIZE =
            5L * 1024 * 1024;

    private static final Set<String>
            SUPPORTED_CONTENT_TYPES =
            Set.of(
                    "image/jpeg",
                    "image/png",
                    "image/webp"
            );

    private static final Set<String>
            SUPPORTED_EXTENSIONS =
            Set.of(
                    "jpg",
                    "jpeg",
                    "png",
                    "webp"
            );

    private final WikiImageStoragePort
            imageStoragePort;
    
    private final WikiImageRepositoryPort
    imageRepositoryPort;

    public UploadWikiImageUseCase(
            WikiImageStoragePort imageStoragePort,
            WikiImageRepositoryPort imageRepositoryPort
    ) {
        this.imageStoragePort =
                Objects.requireNonNull(
                        imageStoragePort,
                        "WikiImageStoragePort không được để trống."
                );

        this.imageRepositoryPort =
                Objects.requireNonNull(
                        imageRepositoryPort,
                        "WikiImageRepositoryPort không được để trống."
                );
    }

    public WikiImageUploadResult execute(
            String originalFilename,
            String contentType,
            byte[] content
    ) {
        validate(
                originalFilename,
                contentType,
                content
        );

        String contentHash =
                calculateSha256(
                        content
                );

        /*
         * Nếu binary của ảnh đã từng được upload,
         * tái sử dụng asset Cloudinary cũ.
         */
        return imageRepositoryPort
                .findByContentHash(
                        contentHash
                )
                .map(
                        existingAsset ->
                                new WikiImageUploadResult(
                                        existingAsset.url(),
                                        existingAsset.publicId()
                                )
                )
                .orElseGet(
                        () -> uploadNewImage(
                                originalFilename,
                                contentType,
                                content,
                                contentHash
                        )
                );
    }
    
    private WikiImageUploadResult uploadNewImage(
            String originalFilename,
            String contentType,
            byte[] content,
            String contentHash
    ) {
        WikiImageUploadResult uploadResult =
                imageStoragePort.upload(
                        normalizeFilename(
                                originalFilename
                        ),
                        contentType,
                        content
                );

        WikiImageAsset asset =
                new WikiImageAsset(
                        UUID.randomUUID(),
                        contentHash,
                        uploadResult.url(),
                        uploadResult.publicId(),
                        contentType,
                        content.length,
                        Instant.now()
                );

        imageRepositoryPort.save(
                asset
        );

        return uploadResult;
    }
    
    private String calculateSha256(
            byte[] content
    ) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance(
                            "SHA-256"
                    );

            byte[] hash =
                    digest.digest(
                            content
                    );

            return HexFormat
                    .of()
                    .formatHex(
                            hash
                    );

        } catch (
                NoSuchAlgorithmException exception
        ) {
            /*
             * SHA-256 là thuật toán bắt buộc
             * phải có trong Java runtime.
             */
            throw new IllegalStateException(
                    "Không thể tạo fingerprint cho ảnh Wiki.",
                    exception
            );
        }
    }

    /*
     * =====================================================
     * VALIDATION
     * =====================================================
     */

    private void validate(
            String originalFilename,
            String contentType,
            byte[] content
    ) {
        validateContent(
                content
        );

        validateContentType(
                contentType
        );

        validateFilename(
                originalFilename
        );
    }

    private void validateContent(
            byte[] content
    ) {
        if (
                content == null
                || content.length == 0
        ) {
            throw new IllegalArgumentException(
                    "Vui lòng chọn một ảnh Wiki."
            );
        }

        if (
                content.length > MAX_FILE_SIZE
        ) {
            throw new IllegalArgumentException(
                    "Ảnh Wiki không được vượt quá 5 MB."
            );
        }
    }

    private void validateContentType(
            String contentType
    ) {
        if (
                contentType == null
                || !SUPPORTED_CONTENT_TYPES.contains(
                        contentType
                )
        ) {
            throw new IllegalArgumentException(
                    "Chỉ chấp nhận ảnh JPG, PNG hoặc WEBP."
            );
        }
    }

    private void validateFilename(
            String originalFilename
    ) {
        if (
                originalFilename == null
                || originalFilename.isBlank()
        ) {
            throw new IllegalArgumentException(
                    "Tên file ảnh không hợp lệ."
            );
        }

        String extension =
                extractExtension(
                        originalFilename
                );

        if (
                !SUPPORTED_EXTENSIONS.contains(
                        extension
                )
        ) {
            throw new IllegalArgumentException(
                    "Chỉ chấp nhận ảnh JPG, PNG hoặc WEBP."
            );
        }
    }

    /*
     * =====================================================
     * FILENAME
     * =====================================================
     */

    private String normalizeFilename(
            String originalFilename
    ) {
        String normalized =
                originalFilename.trim();

        /*
         * Phòng trường hợp client gửi cả đường dẫn:
         *
         * C:\fakepath\image.png
         * /home/user/image.png
         */
        normalized =
                normalized.replace(
                        '\\',
                        '/'
                );

        int lastSlashIndex =
                normalized.lastIndexOf('/');

        if (
                lastSlashIndex >= 0
                && lastSlashIndex
                        < normalized.length() - 1
        ) {
            normalized =
                    normalized.substring(
                            lastSlashIndex + 1
                    );
        }

        return normalized;
    }

    private String extractExtension(
            String originalFilename
    ) {
        if (
                originalFilename == null
                || originalFilename.isBlank()
        ) {
            return "";
        }

        String normalizedFilename =
                normalizeFilename(
                        originalFilename
                );

        int dotIndex =
                normalizedFilename.lastIndexOf('.');

        if (
                dotIndex < 0
                || dotIndex
                        == normalizedFilename.length() - 1
        ) {
            return "";
        }

        return normalizedFilename
                .substring(
                        dotIndex + 1
                )
                .toLowerCase(
                        Locale.ROOT
                );
    }
}