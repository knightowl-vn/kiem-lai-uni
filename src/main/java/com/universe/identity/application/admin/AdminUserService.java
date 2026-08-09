package com.universe.identity.application.admin;

import com.universe.identity.application.ports.IdentityAdminQueryPort;
import com.universe.identity.application.ports.UserRepositoryPort;
import com.universe.identity.contracts.admin.dto.AdminUserDetailView;
import com.universe.identity.contracts.admin.dto.AdminUserView;
import com.universe.identity.domain.Email;
import com.universe.identity.domain.User;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
public class AdminUserService {

	private static final int DEFAULT_PAGE_SIZE = 10;
	private static final int MAX_PAGE_SIZE = 50;

	private final IdentityAdminQueryPort adminQuery;
	private final UserRepositoryPort userRepository;

	public AdminUserService(IdentityAdminQueryPort adminQuery, UserRepositoryPort userRepository) {
		this.adminQuery = adminQuery;
		this.userRepository = userRepository;
	}

	/**
	 * Lấy danh sách người dùng có tìm kiếm, lọc, sắp xếp và phân trang.
	 */
	@Transactional(readOnly = true)
	public Page<AdminUserView> findUsers(String keyword, String status, String role, String authProvider, int page,
			int size) {
		int safePage = Math.max(page, 0);

		int safeSize = Math.min(Math.max(size, DEFAULT_PAGE_SIZE), MAX_PAGE_SIZE);

		Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));

		return adminQuery.searchUsers(normalizeText(keyword), normalizeEnumText(status), parseOptionalRole(role),
				normalizeEnumText(authProvider), pageable);
	}

	/**
	 * Xem chi tiết một người dùng.
	 */
	@Transactional(readOnly = true)
	public AdminUserDetailView getUserDetail(String userId) {
		UUID targetUserId = parseUserId(userId);

		return adminQuery.findUserDetail(targetUserId)
				.orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người dùng."));
	}

	/**
	 * Khóa hoặc mở khóa tài khoản.
	 */
	@Transactional
	public void changeStatus(
	        String targetUserId,
	        String newStatus,
	        String currentAdminEmail
	) {
	    User actor =
	            getCurrentActor(
	                    currentAdminEmail
	            );

	    User targetUser =
	            getUserById(
	                    targetUserId
	            );

	    UserStatus targetStatus =
	            parseRequiredStatus(
	                    newStatus
	            );

	    /*
	     * Không ai được tự thay đổi trạng thái chính mình.
	     */
	    if (actor.getId().equals(targetUser.getId())) {
	        throw new IllegalStateException(
	                "Bạn không thể tự thay đổi trạng thái tài khoản của mình."
	        );
	    }

	    /*
	     * SUPER_ADMIN được bảo vệ tuyệt đối.
	     */
	    if (targetUser.getRole()
	            == UserRole.SUPER_ADMIN) {

	        throw new IllegalStateException(
	                "Không thể thay đổi trạng thái của Super Admin."
	        );
	    }

	    /*
	     * ADMIN chỉ được quản lý USER.
	     */
	    if (actor.getRole() == UserRole.ADMIN
	            && targetUser.getRole()
	            != UserRole.USER) {

	        throw new IllegalStateException(
	                "Admin chỉ được quản lý tài khoản User."
	        );
	    }

	    /*
	     * SUPER_ADMIN được quản lý USER và ADMIN.
	     */
	    if (actor.getRole() == UserRole.SUPER_ADMIN
	            && targetUser.getRole()
	            != UserRole.USER
	            && targetUser.getRole()
	            != UserRole.ADMIN) {

	        throw new IllegalStateException(
	                "Không thể thay đổi trạng thái tài khoản này."
	        );
	    }

	    if (targetUser.getStatus() == targetStatus) {
	        return;
	    }

	    switch (targetStatus) {
	        case ACTIVE ->
	                targetUser.activate();

	        case BLOCKED ->
	                targetUser.block();

	        default ->
	                throw new IllegalStateException(
	                        "Chỉ có thể khóa hoặc mở khóa tài khoản."
	                );
	    }

	    userRepository.save(
	            targetUser
	    );
	}

	/**
	 * Nâng hoặc hạ quyền tài khoản.
	 */
	@Transactional
	public void changeRole(
	        String targetUserId,
	        String newRole,
	        String currentAdminEmail
	) {
	    User actor =
	            getCurrentActor(
	                    currentAdminEmail
	            );

	    User targetUser =
	            getUserById(
	                    targetUserId
	            );

	    UserRole targetRole =
	            parseRequiredManageableRole(
	                    newRole
	            );

	    /*
	     * Chỉ SUPER_ADMIN được thay đổi role.
	     */
	    if (actor.getRole()
	            != UserRole.SUPER_ADMIN) {

	        throw new IllegalStateException(
	                "Chỉ Super Admin mới được thay đổi quyền tài khoản."
	        );
	    }

	    /*
	     * Không được tự thay đổi role.
	     */
	    if (actor.getId().equals(targetUser.getId())) {
	        throw new IllegalStateException(
	                "Super Admin không thể tự thay đổi quyền của chính mình."
	        );
	    }

	    /*
	     * Không được tác động đến SUPER_ADMIN.
	     */
	    if (targetUser.getRole()
	            == UserRole.SUPER_ADMIN) {

	        throw new IllegalStateException(
	                "Không thể thay đổi quyền của Super Admin."
	        );
	    }

	    /*
	     * Chỉ cho chuyển đổi USER và ADMIN.
	     * Không cho cấp SUPER_ADMIN từ giao diện.
	     */
	    if (targetRole != UserRole.USER
	            && targetRole != UserRole.ADMIN) {

	        throw new IllegalArgumentException(
	                "Chỉ có thể cấp quyền User hoặc Admin."
	        );
	    }

	    if (targetUser.getRole() == targetRole) {
	        return;
	    }

	    targetUser.changeRole(
	            targetRole
	    );

	    userRepository.save(
	            targetUser
	    );
	}
	/**
	 * Tìm aggregate User theo ID.
	 */
	private User getUserById(String userId) {
		UUID parsedUserId = parseUserId(userId);

		return userRepository.findById(parsedUserId)
				.orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người dùng."));
	}

	private UUID parseUserId(String userId) {
		if (userId == null || userId.isBlank()) {

			throw new IllegalArgumentException("ID người dùng không hợp lệ.");
		}

		try {
			return UUID.fromString(userId.trim());

		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("ID người dùng không đúng định dạng UUID.", exception);
		}
	}

	/**
	 * Chuẩn hóa từ khóa tìm kiếm.
	 */
	private String normalizeText(String value) {
		if (value == null || value.isBlank()) {

			return null;
		}

		return value.trim();
	}

	/**
	 * Chuẩn hóa giá trị filter: ACTIVE, BLOCKED, LOCAL, GOOGLE...
	 */
	private String normalizeEnumText(String value) {
		if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value)) {

			return null;
		}

		return value.trim().toUpperCase(Locale.ROOT);
	}

	/**
	 * Parse role dùng cho bộ lọc.
	 *
	 * Giá trị sai được xem như không lọc.
	 */
	private UserRole parseOptionalRole(String value) {
		if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value)) {

			return null;
		}

		try {
			return UserRole.valueOf(value.trim().toUpperCase(Locale.ROOT));

		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	

	/**
	 * Admin hiện chỉ được đổi giữa ACTIVE và BLOCKED.
	 */
	private UserStatus parseRequiredStatus(String value) {
		if (value == null || value.isBlank()) {

			throw new IllegalArgumentException("Trạng thái tài khoản không hợp lệ.");
		}

		String normalizedStatus = value.trim().toUpperCase(Locale.ROOT);

		try {
			UserStatus status = UserStatus.valueOf(normalizedStatus);

			if (status != UserStatus.ACTIVE && status != UserStatus.BLOCKED) {

				throw new IllegalArgumentException("Chỉ có thể khóa " + "hoặc mở khóa tài khoản.");
			}

			return status;

		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("Chỉ có thể khóa " + "hoặc mở khóa tài khoản.");
		}
	}

	/**
	 * Chuẩn hóa email Admin hiện tại.
	 */
	private String normalizeRequiredEmail(String email) {
		if (email == null || email.isBlank()) {

			throw new IllegalStateException("Không xác định được tài khoản " + "Admin đang đăng nhập.");
		}

		return email.trim().toLowerCase(Locale.ROOT);
	}
	
	private User getCurrentActor(
	        String currentAdminEmail
	) {
	    Email email =
	            new Email(
	                    normalizeRequiredEmail(
	                            currentAdminEmail
	                    )
	            );

	    User actor =
	            userRepository
	                    .findByEmail(email)
	                    .orElseThrow(() ->
	                            new IllegalStateException(
	                                    "Không tìm thấy tài khoản quản trị đang đăng nhập."
	                            )
	                    );

	    if (actor.getStatus() != UserStatus.ACTIVE) {
	        throw new IllegalStateException(
	                "Tài khoản quản trị không ở trạng thái hoạt động."
	        );
	    }

	    if (actor.getRole() != UserRole.ADMIN
	            && actor.getRole() != UserRole.SUPER_ADMIN) {

	        throw new IllegalStateException(
	                "Tài khoản không có quyền quản trị."
	        );
	    }

	    return actor;
	}
	
	@Transactional(readOnly = true)
	public UserRole getCurrentActorRole(
	        String currentAdminEmail
	) {
	    return getCurrentActor(
	            currentAdminEmail
	    ).getRole();
	}
	
	private UserRole parseRequiredManageableRole(
	        String value
	) {
	    if (value == null || value.isBlank()) {
	        throw new IllegalArgumentException(
	                "Quyền người dùng không hợp lệ."
	        );
	    }

	    String normalizedRole =
	            value.trim()
	                    .toUpperCase(Locale.ROOT);

	    try {
	        UserRole role =
	                UserRole.valueOf(
	                        normalizedRole
	                );

	        if (role != UserRole.USER
	                && role != UserRole.ADMIN) {

	            throw new IllegalArgumentException(
	                    "Chỉ có thể cấp quyền User hoặc Admin."
	            );
	        }

	        return role;

	    } catch (IllegalArgumentException exception) {
	        throw new IllegalArgumentException(
	                "Chỉ có thể cấp quyền User hoặc Admin."
	        );
	    }
	}
}