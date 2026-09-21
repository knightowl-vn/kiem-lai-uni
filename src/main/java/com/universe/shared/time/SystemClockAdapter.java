package com.universe.shared.time;

import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
public class SystemClockAdapter implements ClockPort {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Override
    public Instant now() {
        return Instant.now();
    }
}