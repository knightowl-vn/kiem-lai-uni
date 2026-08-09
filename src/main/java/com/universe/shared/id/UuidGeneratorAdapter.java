package com.universe.shared.id;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class UuidGeneratorAdapter
        implements IdGeneratorPort {

    @Override
    public UUID generate() {
        return UUID.randomUUID();
    }
}