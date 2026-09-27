package com.universe.identity.contracts.interfaces;

import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.dto.UserPublicProfileDTO;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Public Contract của Identity Module.
 *
 * Cho phép các module khác tra cứu thông tin định danh cơ bản
 * mà không cần truy cập trực tiếp Domain, Repository hoặc JPA Entity
 * của Identity.
 */
public interface UserIdentityContract {

    /**
     * Tìm người dùng theo ID.
     *
     * @param userId ID người dùng
     * @return thông tin người dùng nếu tồn tại
     */
    Optional<UserDTO> findById(UUID userId);

    /**
     * Tìm người dùng theo email.
     *
     * @param email email người dùng
     * @return thông tin người dùng nếu tồn tại
     */
    Optional<UserDTO> findByEmail(String email);

    /**
     * Kiểm tra người dùng có tồn tại hay không.
     *
     * Các module khác có thể sử dụng hàm này để kiểm tra
     * actorId, authorId, mentionedUserId...
     *
     * @param userId ID người dùng
     * @return true nếu người dùng tồn tại
     */
    boolean existsById(UUID userId);

    /**
     * Tra cứu thông tin hồ sơ công khai theo danh sách ID người dùng.
     *
     * @param userIds tập hợp ID người dùng cần tra cứu
     * @return Map ánh xạ từ userId sang UserPublicProfileDTO (chỉ chứa các user tìm thấy)
     */
    default Map<UUID, UserPublicProfileDTO> findPublicProfilesByIds(Set<UUID> userIds) {
        return Map.of();
    }
}