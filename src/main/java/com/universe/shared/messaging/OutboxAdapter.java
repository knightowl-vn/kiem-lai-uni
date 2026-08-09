package com.universe.shared.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.universe.shared.events.DomainEvent;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class OutboxAdapter implements OutboxPort {

    private static final String STATUS_PENDING =
            "PENDING";

    private final SpringDataOutboxMessageJpaRepository
            outboxRepository;

    private final ObjectMapper
            objectMapper;

    public OutboxAdapter(
            SpringDataOutboxMessageJpaRepository outboxRepository,
            ObjectMapper objectMapper
    ) {
        this.outboxRepository =
                outboxRepository;

        this.objectMapper =
                objectMapper;
    }

    @Override
    public void saveEvent(
            DomainEvent domainEvent,
            String aggregateType,
            long aggregateVersion,
            String sourceModule
    ) {
        validateDomainEvent(domainEvent);

        String normalizedAggregateType =
                requireText(
                        aggregateType,
                        "Aggregate type không được để trống."
                );

        String normalizedSourceModule =
                requireText(
                        sourceModule,
                        "Source module không được để trống."
                );

        if (aggregateVersion < 1L) {
            throw new IllegalArgumentException(
                    "Aggregate version phải lớn hơn hoặc bằng 1."
            );
        }

        OutboxMessageJpaEntity entity =
                new OutboxMessageJpaEntity();

        /*
         * ID riêng của bản ghi trong bảng Outbox.
         */
        entity.setId(
                UUID.randomUUID().toString()
        );

        /*
         * ID bất biến của domain event.
         */
        entity.setEventId(
                domainEvent.eventId().toString()
        );

        entity.setEventType(
                domainEvent.eventType().trim()
        );

        entity.setEventVersion(
                domainEvent.eventVersion().trim()
        );

        entity.setAggregateId(
                domainEvent.aggregateId().toString()
        );

        entity.setAggregateType(
                normalizedAggregateType
        );

        entity.setAggregateVersion(
                aggregateVersion
        );

        entity.setSourceModule(
                normalizedSourceModule
        );

        entity.setOccurredAt(
                domainEvent.occurredAt()
        );

        entity.setPayloadJson(
                serializeEvent(domainEvent)
        );

        entity.setStatus(
                STATUS_PENDING
        );

        entity.setAttemptCount(
                0
        );

        /*
         * createdAt do MySQL tự tạo.
         * persistenceVersion do Hibernate quản lý.
         *
         * Các field phục vụ publisher/retry hiện được để null.
         */
        outboxRepository.save(entity);
    }

    private void validateDomainEvent(
            DomainEvent domainEvent
    ) {
        if (domainEvent == null) {
            throw new IllegalArgumentException(
                    "Domain event không được để trống."
            );
        }

        if (domainEvent.eventId() == null) {
            throw new IllegalArgumentException(
                    "Event ID không được để trống."
            );
        }

        requireText(
                domainEvent.eventType(),
                "Event type không được để trống."
        );

        requireText(
                domainEvent.eventVersion(),
                "Event version không được để trống."
        );

        if (domainEvent.aggregateId() == null) {
            throw new IllegalArgumentException(
                    "Aggregate ID không được để trống."
            );
        }

        if (domainEvent.occurredAt() == null) {
            throw new IllegalArgumentException(
                    "Thời gian phát sinh event không được để trống."
            );
        }
    }

    private String requireText(
            String value,
            String errorMessage
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    errorMessage
            );
        }

        return value.trim();
    }

    private String serializeEvent(
            DomainEvent domainEvent
    ) {
        try {
            return objectMapper.writeValueAsString(
                    domainEvent
            );

        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Không thể chuyển domain event thành JSON.",
                    exception
            );
        }
    }
}