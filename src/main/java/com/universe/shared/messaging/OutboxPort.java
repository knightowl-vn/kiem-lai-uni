package com.universe.shared.messaging;

import com.universe.shared.events.DomainEvent;

/**
 * Port kỹ thuật phục vụ Transactional Outbox Pattern.
 *
 * Domain event được lưu trong cùng database transaction
 * với aggregate đã phát sinh event.
 */
public interface OutboxPort {

    /**
     * Lưu domain event vào bảng outbox.
     *
     * @param domainEvent      sự kiện nghiệp vụ
     * @param aggregateType    loại aggregate, ví dụ "User"
     * @param aggregateVersion version hiện tại của aggregate
     * @param sourceModule     module phát sinh event, ví dụ "Identity"
     */
    void saveEvent(
            DomainEvent domainEvent,
            String aggregateType,
            long aggregateVersion,
            String sourceModule
    );
}