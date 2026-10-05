package com.universe.identity.application.registration;

import com.universe.identity.application.password.PasswordPolicy;
import com.universe.identity.application.ports.PasswordHasherPort;
import com.universe.identity.application.ports.UserRepositoryPort;
import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.domain.Email;
import com.universe.identity.domain.PublicHandleGenerator;
import com.universe.identity.domain.exceptions.DuplicatePublicHandleException;
import com.universe.identity.domain.exceptions.EmailAlreadyExistsException;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class RegisterUserUseCase {

    private final UserRepositoryPort userRepositoryPort;
    private final PasswordHasherPort passwordHasherPort;
    private final PasswordPolicy passwordPolicy;
    private final IdGeneratorPort idGeneratorPort;
    private final ClockPort clockPort;
    private final RegisterUserAttemptExecutor attemptExecutor;

    public RegisterUserUseCase(
            UserRepositoryPort userRepositoryPort,
            PasswordHasherPort passwordHasherPort,
            PasswordPolicy passwordPolicy,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort,
            RegisterUserAttemptExecutor attemptExecutor
    ) {
        this.userRepositoryPort = Objects.requireNonNull(userRepositoryPort, "userRepositoryPort cannot be null");
        this.passwordHasherPort = Objects.requireNonNull(passwordHasherPort, "passwordHasherPort cannot be null");
        this.passwordPolicy = Objects.requireNonNull(passwordPolicy, "passwordPolicy cannot be null");
        this.idGeneratorPort = Objects.requireNonNull(idGeneratorPort, "idGeneratorPort cannot be null");
        this.clockPort = Objects.requireNonNull(clockPort, "clockPort cannot be null");
        this.attemptExecutor = Objects.requireNonNull(attemptExecutor, "attemptExecutor cannot be null");
    }

    public UserDTO execute(RegisterUserCommand command) {
        Objects.requireNonNull(command, "command cannot be null");

        Email email = new Email(command.email());

        if (userRepositoryPort.existsByEmail(email)) {
            throw new EmailAlreadyExistsException("Email đã được sử dụng.");
        }

        passwordPolicy.validate(command.password());

        String passwordHash = passwordHasherPort.hash(command.password());
        UUID userId = idGeneratorPort.generate();
        Instant now = clockPort.now();

        int finalAttemptIndex = PublicHandleGenerator.MAX_COLLISION_ATTEMPTS + 1;
        for (int attempt = 0; attempt <= finalAttemptIndex; attempt++) {
            String candidateHandle = PublicHandleGenerator.candidateForAttempt(
                    command.displayName(),
                    userId,
                    attempt
            );

            if (attempt <= PublicHandleGenerator.MAX_COLLISION_ATTEMPTS
                    && userRepositoryPort.existsByPublicHandle(candidateHandle)) {
                continue;
            }

            try {
                return attemptExecutor.executeAttempt(
                        userId,
                        email,
                        passwordHash,
                        command.displayName(),
                        candidateHandle,
                        now
                );
            } catch (DuplicatePublicHandleException ex) {
                if (attempt >= finalAttemptIndex) {
                    throw new IllegalStateException(
                            "Không thể cấp phát public handle duy nhất sau " + (finalAttemptIndex + 1) + " lần thử.",
                            ex
                    );
                }
            }
        }

        throw new IllegalStateException("Không thể cấp phát public handle duy nhất.");
    }
}