package com.universe.identity.infrastructure.storage;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.universe.identity.application.ports.AvatarStoragePort;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Component
public class CloudinaryAvatarStorageAdapter
        implements AvatarStoragePort {

    private static final long MAX_FILE_SIZE =
            2L * 1024 * 1024;

    private static final String AVATAR_FOLDER =
            "kiemlai/avatars";

    private final Cloudinary cloudinary;

    public CloudinaryAvatarStorageAdapter(
            Cloudinary cloudinary
    ) {
        this.cloudinary =
                Objects.requireNonNull(
                        cloudinary,
                        "Cloudinary không được để trống."
                );
    }

    @Override
    public String uploadAvatar(
            UUID userId,
            MultipartFile file
    ) {
        validateUserId(userId);
        validateFile(file);

        try {
            String publicId =
                    buildPublicId(userId);

            Map<?, ?> uploadResult =
                    cloudinary
                            .uploader()
                            .upload(
                                    file.getBytes(),
                                    ObjectUtils.asMap(
                                            "asset_folder",
                                            AVATAR_FOLDER,

                                            "public_id",
                                            publicId,

                                            "overwrite",
                                            true,

                                            "invalidate",
                                            true,

                                            "resource_type",
                                            "image"
                                    )
                            );

            Object secureUrl =
                    uploadResult.get(
                            "secure_url"
                    );

            if (secureUrl == null
                    || secureUrl.toString().isBlank()) {

                throw new IllegalStateException(
                        "Cloudinary không trả về secure_url."
                );
            }

            return secureUrl.toString();

        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Không thể tải ảnh đại diện lên Cloudinary.",
                    exception
            );
        }
    }

    @Override
    public void deleteAvatar(
            UUID userId
    ) {
        validateUserId(userId);

        try {
            String publicId =
                    buildPublicId(userId);

            Map<?, ?> result =
                    cloudinary
                            .uploader()
                            .destroy(
                                    publicId,
                                    ObjectUtils.asMap(
                                            "resource_type",
                                            "image",

                                            "invalidate",
                                            true
                                    )
                            );

            String status =
                    String.valueOf(
                            result.get("result")
                    );

            if (!"ok".equalsIgnoreCase(status)
                    && !"not found".equalsIgnoreCase(status)) {

                throw new IllegalStateException(
                        "Cloudinary xóa avatar thất bại: "
                                + status
                );
            }

        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Không thể kết nối Cloudinary để xóa ảnh đại diện.",
                    exception
            );
        }
    }

    private String buildPublicId(
            UUID userId
    ) {
        return AVATAR_FOLDER
                + "/"
                + userId;
    }

    private void validateUserId(
            UUID userId
    ) {
        if (userId == null) {
            throw new IllegalArgumentException(
                    "User ID không được để trống."
            );
        }
    }

    private void validateFile(
            MultipartFile file
    ) {
        if (file == null
                || file.isEmpty()) {

            throw new IllegalArgumentException(
                    "Vui lòng chọn một ảnh đại diện."
            );
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException(
                    "Ảnh đại diện không được vượt quá 2 MB."
            );
        }

        String contentType =
                file.getContentType();

        boolean supportedType =
                "image/jpeg".equals(contentType)
                        || "image/png".equals(contentType)
                        || "image/webp".equals(contentType);

        if (!supportedType) {
            throw new IllegalArgumentException(
                    "Chỉ chấp nhận ảnh JPG, PNG hoặc WEBP."
            );
        }
    }
}