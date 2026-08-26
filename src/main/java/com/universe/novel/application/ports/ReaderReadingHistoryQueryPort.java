package com.universe.novel.application.ports;

import com.universe.novel.contracts.dto.reader.ReaderReadingHistoryDTO;

import java.util.List;
import java.util.UUID;

/**
 * Query Port cho các tác vụ đọc/truy vấn danh sách lịch sử đọc của độc giả.
 */
public interface ReaderReadingHistoryQueryPort {

    List<ReaderReadingHistoryDTO> findReadingHistoryByUserId(UUID userId);
}
