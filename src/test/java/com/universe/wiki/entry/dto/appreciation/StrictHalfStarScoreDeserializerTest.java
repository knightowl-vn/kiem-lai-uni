package com.universe.wiki.entry.dto.appreciation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("StrictHalfStarScoreDeserializer Unit Tests")
class StrictHalfStarScoreDeserializerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    record TestPayload(
            @JsonDeserialize(using = StrictHalfStarScoreDeserializer.class)
            BigDecimal value
    ) {}

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"value\": 1}",
            "{\"value\": 1.0}",
            "{\"value\": 1.5}",
            "{\"value\": 2}",
            "{\"value\": 2.0}",
            "{\"value\": 2.5}",
            "{\"value\": 3}",
            "{\"value\": 3.0}",
            "{\"value\": 3.5}",
            "{\"value\": 4}",
            "{\"value\": 4.0}",
            "{\"value\": 4.5}",
            "{\"value\": 5}",
            "{\"value\": 5.0}"
    })
    @DisplayName("Chấp nhận đúng các giá trị hợp lệ từ 1.0 đến 5.0 bước 0.5")
    void shouldAcceptValidHalfStarNumbers(String json) throws Exception {
        TestPayload payload = objectMapper.readValue(json, TestPayload.class);
        assertThat(payload).isNotNull();
        assertThat(payload.value()).isNotNull();

        BigDecimal doubled = payload.value().multiply(BigDecimal.valueOf(2));
        assertThat(doubled.stripTrailingZeros().scale()).isLessThanOrEqualTo(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"value\": 0.5}",
            "{\"value\": 0.9}",
            "{\"value\": 1.2}",
            "{\"value\": 4.7}",
            "{\"value\": 5.1}",
            "{\"value\": 5.5}",
            "{\"value\": 4.5000000000000001}",
            "{\"value\": 1.2500000000000000}",
            "{\"value\": \"4.5\"}",
            "{\"value\": true}",
            "{\"value\": false}",
            "{\"value\": [4.5]}",
            "{\"value\": {\"val\": 4.5}}"
    })
    @DisplayName("Từ chối các giá trị không hợp lệ: chuỗi, boolean, mảng, object, và số ngoài bước 0.5")
    void shouldRejectInvalidPayloads(String json) {
        assertThatThrownBy(() -> objectMapper.readValue(json, TestPayload.class))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("Bảo toàn giá trị null khi token là null")
    void shouldReturnNullForNullToken() throws Exception {
        TestPayload payload = objectMapper.readValue("{\"value\": null}", TestPayload.class);
        assertThat(payload).isNotNull();
        assertThat(payload.value()).isNull();
    }
}
