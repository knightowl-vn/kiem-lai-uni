package com.universe.identity.application.registration;

import com.universe.identity.application.password.PasswordPolicy;
import com.universe.identity.application.ports.PasswordHasherPort;
import com.universe.identity.application.ports.UserRepositoryPort;
import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.events.UserRegisteredEvent;
import com.universe.identity.domain.Email;
import com.universe.identity.domain.User;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.identity.domain.exceptions.EmailAlreadyExistsException;
import com.universe.identity.domain.exceptions.WeakPasswordException;
import com.universe.shared.events.DomainEvent;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.messaging.OutboxPort;
import com.universe.shared.time.ClockPort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegisterUserUseCaseTest {

	private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");

	private static final String RAW_PASSWORD = "Password123";

	private static final String HASHED_PASSWORD = "$2a$10$fakeHashedPassword";

	@Mock
	private UserRepositoryPort userRepositoryPort;

	@Mock
	private PasswordHasherPort passwordHasherPort;

	@Mock
	private IdGeneratorPort idGeneratorPort;

	@Mock
	private ClockPort clockPort;

	@Mock
	private OutboxPort outboxPort;

	private PasswordPolicy passwordPolicy;

	private RegisterUserUseCase registerUserUseCase;

	@BeforeEach
	void setUp() {
		passwordPolicy = new PasswordPolicy();

		registerUserUseCase = new RegisterUserUseCase(userRepositoryPort, passwordHasherPort, passwordPolicy,
				idGeneratorPort, clockPort, outboxPort);
	}

	@Test
	@DisplayName("Đăng ký thành công lưu password hash và domain event")
	void shouldRegisterUserSuccessfully() {
		RegisterUserCommand command = new RegisterUserCommand("athena@example.com", RAW_PASSWORD, "Athena");

		when(userRepositoryPort.existsByEmail(any(Email.class))).thenReturn(false);

		when(passwordHasherPort.hash(RAW_PASSWORD)).thenReturn(HASHED_PASSWORD);

		when(idGeneratorPort.generate()).thenReturn(USER_ID);

		when(clockPort.now()).thenReturn(NOW);

		UserDTO result = registerUserUseCase.execute(command);

		ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

		verify(userRepositoryPort).save(userCaptor.capture());

		User savedUser = userCaptor.getValue();

		assertThat(savedUser.getId()).isEqualTo(USER_ID);

		assertThat(savedUser.getEmail().value()).isEqualTo("athena@example.com");

		assertThat(savedUser.getPasswordHash()).isEqualTo(HASHED_PASSWORD);

		assertThat(savedUser.getPasswordHash()).isNotEqualTo(RAW_PASSWORD);

		assertThat(savedUser.getDisplayName()).isEqualTo("Athena");

		assertThat(savedUser.getStatus()).isEqualTo(UserStatus.ACTIVE);

		assertThat(savedUser.getRole()).isEqualTo(UserRole.USER);

		verify(passwordHasherPort).hash(RAW_PASSWORD);

		ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);

		verify(outboxPort).saveEvent(eventCaptor.capture(), eq("User"), eq(1L), eq("Identity"));

		DomainEvent capturedEvent = eventCaptor.getValue();

		assertThat(capturedEvent).isInstanceOf(UserRegisteredEvent.class);

		UserRegisteredEvent registeredEvent = (UserRegisteredEvent) capturedEvent;

		assertThat(registeredEvent.aggregateId()).isEqualTo(USER_ID);

		assertThat(registeredEvent.email()).isEqualTo("athena@example.com");

		assertThat(registeredEvent.displayName()).isEqualTo("Athena");

		assertThat(registeredEvent.occurredAt()).isEqualTo(NOW);

		assertThat(savedUser.domainEventsSnapshot()).isEmpty();

		assertThat(result.id()).isEqualTo(USER_ID);

		assertThat(result.email()).isEqualTo("athena@example.com");

		assertThat(result.displayName()).isEqualTo("Athena");

		assertThat(result.avatarUrl()).isNull();

		assertThat(result.status()).isEqualTo("ACTIVE");

		assertThat(result.role()).isEqualTo("USER");

		assertThat(result.createdAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("Email đã tồn tại thì không hash, không lưu user và không ghi outbox")
	void shouldRejectRegistrationWhenEmailAlreadyExists() {
		RegisterUserCommand command = new RegisterUserCommand("athena@example.com", RAW_PASSWORD, "Athena");

		when(userRepositoryPort.existsByEmail(any(Email.class))).thenReturn(true);

		assertThatThrownBy(() -> registerUserUseCase.execute(command)).isInstanceOf(EmailAlreadyExistsException.class)
				.hasMessage("Email đã được sử dụng.");

		verify(passwordHasherPort, never()).hash(any());

		verify(idGeneratorPort, never()).generate();

		verify(clockPort, never()).now();

		verify(userRepositoryPort, never()).save(any(User.class));

		verify(outboxPort, never()).saveEvent(any(DomainEvent.class), any(), anyLong(), any());
	}

	@Test
	@DisplayName("Mật khẩu yếu thì không hash, không lưu user và không ghi outbox")
	void shouldRejectWeakPassword() {
		RegisterUserCommand command = new RegisterUserCommand("athena@example.com", "password", "Athena");

		when(userRepositoryPort.existsByEmail(any(Email.class))).thenReturn(false);

		assertThatThrownBy(() -> registerUserUseCase.execute(command)).isInstanceOf(WeakPasswordException.class)
				.hasMessage("Mật khẩu phải có ít nhất một chữ hoa.");

		verify(passwordHasherPort, never()).hash(any());

		verify(userRepositoryPort, never()).save(any(User.class));

		verify(outboxPort, never()).saveEvent(any(DomainEvent.class), any(), anyLong(), any());
	}

	@Test
	@DisplayName("Lỗi khi ghi Outbox làm use case thất bại")
	void shouldFailWhenOutboxSavingFails() {
		RegisterUserCommand command = new RegisterUserCommand("athena@example.com", RAW_PASSWORD, "Athena");

		when(userRepositoryPort.existsByEmail(any(Email.class))).thenReturn(false);

		when(passwordHasherPort.hash(RAW_PASSWORD)).thenReturn(HASHED_PASSWORD);

		when(idGeneratorPort.generate()).thenReturn(USER_ID);

		when(clockPort.now()).thenReturn(NOW);

		doThrow(new IllegalStateException("Không thể lưu Outbox.")).when(outboxPort).saveEvent(any(DomainEvent.class),
				eq("User"), eq(1L), eq("Identity"));

		assertThatThrownBy(() -> registerUserUseCase.execute(command)).isInstanceOf(IllegalStateException.class)
				.hasMessage("Không thể lưu Outbox.");

		verify(userRepositoryPort).save(any(User.class));

		verify(outboxPort).saveEvent(any(DomainEvent.class), eq("User"), eq(1L), eq("Identity"));
	}
}