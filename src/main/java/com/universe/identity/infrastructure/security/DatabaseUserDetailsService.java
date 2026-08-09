package com.universe.identity.infrastructure.security;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.universe.identity.infrastructure.persistence.SpringDataUserJpaRepository;
import com.universe.identity.infrastructure.persistence.UserJpaEntity;

import java.util.Locale;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {

	private final SpringDataUserJpaRepository userRepository;

	public DatabaseUserDetailsService(SpringDataUserJpaRepository userRepository) {
		this.userRepository = userRepository;
	}

	@Override
	public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {

		if (email == null || email.isBlank()) {
			throw new UsernameNotFoundException("Email không hợp lệ.");
		}

		String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);

		UserJpaEntity entity = userRepository.findByEmail(normalizedEmail)
				.orElseThrow(() -> new UsernameNotFoundException("Không tìm thấy tài khoản."));

		/*
		 * Tài khoản Google thuần không có mật khẩu local. Không cho đăng nhập bằng form
		 * email/password.
		 */
		String passwordHash = entity.getPasswordHash();

		if (passwordHash == null || passwordHash.isBlank()) {

			throw new UsernameNotFoundException("Tài khoản này chưa thiết lập mật khẩu.");
		}

		String status = entity.getStatus();

		boolean active = "ACTIVE".equalsIgnoreCase(status);

		boolean blocked = "BLOCKED".equalsIgnoreCase(status);

		String roleName = entity.getRole() == null ? "USER" : entity.getRole().name();

		return User.builder().username(entity.getEmail()).password(passwordHash).authorities("ROLE_" + roleName)

				/*
				 * BLOCKED không bị disabled, mà bị accountLocked để Spring ném LockedException.
				 */
				.disabled(!active && !blocked).accountLocked(blocked)

				.build();
	}
}