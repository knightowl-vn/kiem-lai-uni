package com.universe.identity.application.registration;

import com.universe.identity.application.password.PasswordPolicy;
import com.universe.identity.application.ports.PasswordHasherPort;
import com.universe.identity.application.ports.UserRepositoryPort;
import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.domain.Email;
import com.universe.identity.domain.User;
import com.universe.identity.domain.exceptions.EmailAlreadyExistsException;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.messaging.OutboxPort;
import com.universe.shared.time.ClockPort;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegisterUserUseCase {

    private static final String AGGREGATE_TYPE =
            "User";

    private static final String SOURCE_MODULE =
            "Identity";

    private final UserRepositoryPort
            userRepositoryPort;

    private final PasswordHasherPort
            passwordHasherPort;

    private final PasswordPolicy
            passwordPolicy;

    private final IdGeneratorPort
            idGeneratorPort;

    private final ClockPort
            clockPort;

    private final OutboxPort
            outboxPort;

    public RegisterUserUseCase(
            UserRepositoryPort userRepositoryPort,
            PasswordHasherPort passwordHasherPort,
            PasswordPolicy passwordPolicy,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort,
            OutboxPort outboxPort
    ) {
        this.userRepositoryPort =
                userRepositoryPort;

        this.passwordHasherPort =
                passwordHasherPort;

        this.passwordPolicy =
                passwordPolicy;

        this.idGeneratorPort =
                idGeneratorPort;

        this.clockPort =
                clockPort;

        this.outboxPort =
                outboxPort;
    }

    @Transactional
    public UserDTO execute(
            RegisterUserCommand command
    ) {
        Email email =
                new Email(
                        command.email()
                );

        if (userRepositoryPort.existsByEmail(email)) {
            throw new EmailAlreadyExistsException(
                    "Email đã được sử dụng."
            );
        }

        passwordPolicy.validate(
                command.password()
        );

        String passwordHash =
                passwordHasherPort.hash(
                        command.password()
                );

        User user =
                User.createLocal(
                        idGeneratorPort.generate(),
                        email,
                        passwordHash,
                        command.displayName(),
                        clockPort.now()
                );

        saveUserAndDomainEvents(user);

        return new UserDTO(
                user.getId(),
                user.getEmail().value(),
                user.getDisplayName(),
                user.getAvatarUrl(),
                user.getStatus().name(),
                user.getRole().name(),
                user.getCreatedAt()
        );
    }

    private void saveUserAndDomainEvents(
            User user
    ) {
        userRepositoryPort.save(user);

        user.domainEventsSnapshot()
                .forEach(event ->
                        outboxPort.saveEvent(
                                event,
                                AGGREGATE_TYPE,
                                user.getAggregateVersion(),
                                SOURCE_MODULE
                        )
                );

        /*
         * Chỉ xóa khỏi aggregate sau khi tất cả event
         * đã được đưa vào persistence context của Outbox.
         */
        user.clearDomainEvents();
    }
}