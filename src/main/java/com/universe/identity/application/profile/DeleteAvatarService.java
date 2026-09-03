package com.universe.identity.application.profile;

import com.universe.identity.application.ports.LegacyAvatarStoragePort;
import com.universe.identity.application.ports.UserRepositoryPort;
import com.universe.identity.domain.Email;
import com.universe.identity.domain.User;
import com.universe.media.contracts.dto.MediaAssetDetailDTO;
import com.universe.media.contracts.dto.MediaAssetStatusDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class DeleteAvatarService {

    private final UserRepositoryPort userRepository;
    private final LegacyAvatarStoragePort legacyAvatarStoragePort;
    private final MediaContract mediaContract;

    public DeleteAvatarService(
            UserRepositoryPort userRepository,
            LegacyAvatarStoragePort legacyAvatarStoragePort,
            MediaContract mediaContract
    ) {
        this.userRepository = Objects.requireNonNull(
                userRepository,
                "User repository không được để trống."
        );
        this.legacyAvatarStoragePort = Objects.requireNonNull(
                legacyAvatarStoragePort,
                "Legacy avatar storage port không được để trống."
        );
        this.mediaContract = Objects.requireNonNull(
                mediaContract,
                "MediaContract không được để trống."
        );
    }

    public void execute(String currentUserEmail) {
        Email email = new Email(normalizeEmail(currentUserEmail));

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Không tìm thấy tài khoản đang đăng nhập."));

        UUID mediaAssetId = user.getAvatarMediaAssetId();
        String currentAvatarUrl = user.getAvatarUrl();

        if (mediaAssetId != null) {
            // 1. Media-backed avatar lifecycle
            Optional<MediaAssetDetailDTO> maybeDetail = mediaContract.getAssetDetail(mediaAssetId);
            if (maybeDetail.isEmpty()) {
                throw new IllegalStateException("Không tìm thấy thông tin Media asset của ảnh đại diện: " + mediaAssetId);
            }

            MediaAssetStatusDTO status = maybeDetail.get().status();
            if (status == MediaAssetStatusDTO.ACTIVE || status == MediaAssetStatusDTO.ARCHIVED) {
                mediaContract.delete(mediaAssetId);
            } else if (status == MediaAssetStatusDTO.DELETED) {
                // Already DELETED in Media: skip delete call for retry safety
            } else {
                throw new IllegalStateException("Trạng thái Media asset không hợp lệ để xóa: " + status);
            }

            user.removeAvatar();
            userRepository.save(user);

        } else if (currentAvatarUrl != null && !currentAvatarUrl.isBlank()) {
            if (legacyAvatarStoragePort.isLegacyAvatarUrl(currentAvatarUrl)) {
                // 2. Legacy Cloudinary avatar
                legacyAvatarStoragePort.deleteAvatar(user.getId());
                user.removeAvatar();
                userRepository.save(user);
            } else {
                // 3. External avatar (e.g. Google avatar)
                user.removeAvatar();
                userRepository.save(user);
            }
        } else {
            // 4. No avatar
            long versionBefore = user.getAggregateVersion();
            user.removeAvatar();
            if (user.getAggregateVersion() != versionBefore) {
                userRepository.save(user);
            }
        }
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalStateException("Không xác định được email người dùng.");
        }

        return email.trim().toLowerCase(Locale.ROOT);
    }
}