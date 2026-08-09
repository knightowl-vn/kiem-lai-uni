package com.universe.identity.contracts.interfaces;

import com.universe.identity.contracts.dto.UserDTO;

import java.util.Optional;
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
}