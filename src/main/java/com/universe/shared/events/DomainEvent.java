package com.universe.shared.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Hợp đồng chung cho các domain event có thể được ghi vào Outbox.
 *
 * Shared chỉ biết cấu trúc chung của event,
 * không biết event thuộc Identity, Wiki hay module nào khác.
 */
public interface DomainEvent {

    UUID eventId();

    String eventType();

    UUID aggregateId();

    Instant occurredAt();

    /**
     * Phiên bản cấu trúc của event.
     *
     * Event mới mặc định bắt đầu từ version 1.
     */
    default String eventVersion() {
        return "1";
    }
}