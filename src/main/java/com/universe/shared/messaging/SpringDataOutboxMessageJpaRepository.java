package com.universe.shared.messaging;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataOutboxMessageJpaRepository
        extends JpaRepository<
                OutboxMessageJpaEntity,
                String
        > {
}