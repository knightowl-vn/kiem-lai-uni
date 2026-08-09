package com.universe.identity.application.profile;

import com.universe.identity.application.ports.AvatarStoragePort;
import com.universe.identity.application.ports.UserRepositoryPort;
import com.universe.identity.domain.Email;
import com.universe.identity.domain.User;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Objects;

@Service
public class DeleteAvatarService {

	private final UserRepositoryPort userRepository;
	private final AvatarStoragePort avatarStorage;

	public DeleteAvatarService(UserRepositoryPort userRepository, AvatarStoragePort avatarStorage) {
		this.userRepository = Objects.requireNonNull(userRepository, "User repository không được để trống.");

		this.avatarStorage = Objects.requireNonNull(avatarStorage, "Avatar storage không được để trống.");
	}

	@Transactional
	public void execute(String currentUserEmail) {
		Email email = new Email(normalizeEmail(currentUserEmail));

		User user = userRepository.findByEmail(email)
				.orElseThrow(() -> new IllegalStateException("Không tìm thấy tài khoản đang đăng nhập."));

		/*
		 * Nếu database đã không còn avatar, không cần gọi Cloudinary hoặc save lại.
		 */
		String currentAvatarUrl = user.getAvatarUrl();

		if (currentAvatarUrl != null && !currentAvatarUrl.isBlank()) {

			avatarStorage.deleteAvatar(user.getId());
		}

		user.removeAvatar();

		userRepository.save(user);
	}

	private String normalizeEmail(String email) {
		if (email == null || email.isBlank()) {
			throw new IllegalStateException("Không xác định được email người dùng.");
		}

		return email.trim().toLowerCase(Locale.ROOT);
	}
}