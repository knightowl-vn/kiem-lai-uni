package com.universe.identity.application.password;

import com.universe.identity.application.ports.PasswordHasherPort;
import com.universe.identity.application.ports.PasswordResetTokenRepositoryPort;
import com.universe.identity.application.ports.UserRepositoryPort;
import com.universe.identity.domain.PasswordResetToken;
import com.universe.identity.domain.User;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class ResetPasswordService {

	private final PasswordResetTokenRepositoryPort tokenRepository;
	private final UserRepositoryPort userRepository;
	private final PasswordHasherPort passwordHasher;
	private final PasswordPolicy passwordPolicy;
	private final PasswordUpdater passwordUpdater;
	private final PasswordResetTokenHasher tokenHasher;

	public ResetPasswordService(PasswordResetTokenRepositoryPort tokenRepository, UserRepositoryPort userRepository,
			PasswordHasherPort passwordHasher, PasswordPolicy passwordPolicy, PasswordUpdater passwordUpdater,
			PasswordResetTokenHasher tokenHasher) {
		this.tokenRepository = tokenRepository;
		this.userRepository = userRepository;
		this.passwordHasher = passwordHasher;
		this.passwordPolicy = passwordPolicy;
		this.passwordUpdater = passwordUpdater;
		this.tokenHasher = tokenHasher;
	}

	/**
	 * Kiểm tra token đặt lại mật khẩu còn hợp lệ hay không.
	 */
	@Transactional(readOnly = true)
	public boolean isTokenValid(String rawToken) {
		if (rawToken == null || rawToken.isBlank()) {

			return false;
		}

		String tokenHash = tokenHasher.hash(rawToken);

		Instant now = Instant.now();

		return tokenRepository.findActiveByTokenHash(tokenHash).filter(token -> token.isUsable(now)).isPresent();
	}

	/**
	 * Đặt lại mật khẩu.
	 *
	 * Trả về: - null khi thành công; - thông báo lỗi khi thất bại.
	 */
	@Transactional
	public String resetPassword(String rawToken, String newPassword, String confirmPassword) {
		if (rawToken == null || rawToken.isBlank()) {

			return "Liên kết đặt lại mật khẩu không hợp lệ.";
		}

		/*
		 * Giữ nguyên hành vi cũ: lỗi validation được trả về dưới dạng String.
		 */
		try {
			passwordPolicy.validateConfirmation(newPassword, confirmPassword);

		} catch (IllegalArgumentException exception) {
			return exception.getMessage();
		}

		String tokenHash = tokenHasher.hash(rawToken);

		Instant now = Instant.now();

		PasswordResetToken resetToken = tokenRepository.findActiveByTokenHash(tokenHash).orElse(null);

		if (resetToken == null || !resetToken.isUsable(now)) {

			return "Liên kết đặt lại mật khẩu không hợp lệ hoặc đã hết hạn.";
		}

		User user = userRepository.findById(resetToken.getUserId()).orElse(null);

		if (user == null) {
			return "Không tìm thấy tài khoản.";
		}

		String currentHash = user.getPasswordHash();

		/*
		 * Tài khoản Google thuần có thể chưa có passwordHash.
		 *
		 * Nếu tài khoản đã có mật khẩu thì mật khẩu mới phải khác mật khẩu hiện tại.
		 */
		if (currentHash != null && !currentHash.isBlank() && passwordHasher.verify(newPassword, currentHash)) {

			return "Mật khẩu mới phải khác mật khẩu hiện tại.";
		}

		/*
		 * Hash và cập nhật mật khẩu trên domain User.
		 */
		passwordUpdater.updatePassword(user, newPassword);

		/*
		 * Đánh dấu token vừa sử dụng.
		 */
		resetToken.markUsed(now);

		/*
		 * Vì User và PasswordResetToken hiện là domain object, không còn là JPA managed
		 * entity nên phải lưu rõ ràng.
		 */
		userRepository.save(user);

		tokenRepository.save(resetToken);

		/*
		 * Thu hồi tất cả token còn lại của user.
		 *
		 * Token vừa sử dụng đã có usedAt nên query revoke sẽ không thu hồi lại token
		 * này.
		 */
		tokenRepository.revokeAllActiveByUserId(user.getId(), now);

		return null;
	}
}