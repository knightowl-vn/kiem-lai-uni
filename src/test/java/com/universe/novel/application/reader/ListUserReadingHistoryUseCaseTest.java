package com.universe.novel.application.reader;

import com.universe.novel.application.ports.ReaderReadingHistoryQueryPort;
import com.universe.novel.contracts.dto.reader.ReaderReadingHistoryDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListUserReadingHistoryUseCaseTest {

    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID CHAPTER_1_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID CHAPTER_2_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock
    private ReaderReadingHistoryQueryPort queryPort;

    private ListUserReadingHistoryUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ListUserReadingHistoryUseCase(queryPort);
    }

    @Test
    @DisplayName("execute: trả về danh sách lịch sử đọc được lấy từ query port")
    void shouldReturnReadingHistoryList() {
        Instant t0 = Instant.parse("2026-08-26T08:00:00Z");
        Instant t1 = Instant.parse("2026-08-26T08:30:00Z");

        List<ReaderReadingHistoryDTO> expected = List.of(
                new ReaderReadingHistoryDTO(CHAPTER_2_ID, 2, "Chương 2", "chuong-2", "Quyển 1", t1),
                new ReaderReadingHistoryDTO(CHAPTER_1_ID, 1, "Chương 1", "chuong-1", "Quyển 1", t0)
        );

        when(queryPort.findReadingHistoryByUserId(USER_ID)).thenReturn(expected);

        List<ReaderReadingHistoryDTO> result = useCase.execute(USER_ID);

        assertThat(result).hasSize(2).isEqualTo(expected);
        verify(queryPort).findReadingHistoryByUserId(USER_ID);
    }

    @Test
    @DisplayName("execute: từ chối userId là null")
    void shouldRejectNullUserId() {
        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ID người dùng không được để trống.");
    }

    @Test
    @DisplayName("Constructor: từ chối queryPort là null")
    void shouldRejectNullQueryPort() {
        assertThatThrownBy(() -> new ListUserReadingHistoryUseCase(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ReaderReadingHistoryQueryPort không được để trống.");
    }
}
