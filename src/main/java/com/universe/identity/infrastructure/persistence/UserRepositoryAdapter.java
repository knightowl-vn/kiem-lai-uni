package com.universe.identity.infrastructure.persistence;

import com.universe.identity.application.ports.UserRepositoryPort;
import com.universe.identity.domain.AuthProvider;
import com.universe.identity.domain.Email;
import com.universe.identity.domain.User;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class UserRepositoryAdapter implements UserRepositoryPort {

	private final SpringDataUserJpaRepository jpaRepository;

	private final UserPersistenceMapper mapper;

	public UserRepositoryAdapter(SpringDataUserJpaRepository jpaRepository, UserPersistenceMapper mapper) {
		this.jpaRepository = Objects.requireNonNull(jpaRepository, "Jpa repository không được để trống.");

		this.mapper = Objects.requireNonNull(mapper, "User persistence mapper không được để trống.");
	}

	@Override
	public Optional<User> findByEmail(Email email) {
		if (email == null) {
			return Optional.empty();
		}

		return jpaRepository.findByEmail(email.value()).map(mapper::toDomain);
	}

	@Override
	public Optional<User> findById(UUID userId) {
		if (userId == null) {
			return Optional.empty();
		}

		return jpaRepository.findById(userId.toString()).map(mapper::toDomain);
	}

	@Override
	public Optional<User> findByProviderSubject(AuthProvider authProvider, String providerSubject) {
		if (authProvider == null || providerSubject == null || providerSubject.isBlank()) {

			return Optional.empty();
		}

		return jpaRepository.findByAuthProviderAndProviderSubject(authProvider.name(), providerSubject.trim())
				.map(mapper::toDomain);
	}

	@Override
	public boolean existsByEmail(Email email) {
		if (email == null) {
			return false;
		}

		return jpaRepository.existsByEmail(email.value());
	}

	@Override
	public void save(User user) {
		if (user == null) {
			throw new IllegalArgumentException("User không được để trống.");
		}

		/*
		 * Nếu user đã tồn tại:
		 * - lấy entity hiện tại;
		 * - giữ nguyên persistenceVersion do Hibernate quản lý;
		 * - đồng bộ các field từ domain User.
		 *
		 * Nếu user chưa tồn tại:
		 * - tạo entity mới;
		 * - mapper thiết lập dữ liệu cần thiết.
		 */
		UserJpaEntity entity = jpaRepository.findById(user.getId().toString()).orElseGet(UserJpaEntity::new);

		mapper.updateJpaEntity(user, entity);

		jpaRepository.save(entity);
	}
}