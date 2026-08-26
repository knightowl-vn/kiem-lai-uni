package com.universe.novel.application.reader;

import com.universe.novel.application.ports.ReaderReadingHistoryQueryPort;
import com.universe.novel.contracts.dto.reader.ReaderReadingHistoryDTO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case truy vấn danh sách lịch sử đọc chương của người dùng đã xác thực.
 *
 * Chỉ trả về các chương hiện đang công khai (Chapter PUBLISHED và Volume PUBLISHED),
 * sắp xếp theo thời gian đọc gần nhất (lastReadAt DESC).
 */
@Service
@Transactional(readOnly = true)
public class ListUserReadingHistoryUseCase {

    private final ReaderReadingHistoryQueryPort readerReadingHistoryQueryPort;

    public ListUserReadingHistoryUseCase(
            ReaderReadingHistoryQueryPort readerReadingHistoryQueryPort
    ) {
        this.readerReadingHistoryQueryPort = Objects.requireNonNull(
                readerReadingHistoryQueryPort,
                "ReaderReadingHistoryQueryPort không được để trống."
        );
    }

    public List<ReaderReadingHistoryDTO> execute(UUID userId) {
        Objects.requireNonNull(userId, "ID người dùng không được để trống.");
        return readerReadingHistoryQueryPort.findReadingHistoryByUserId(userId);
    }
}
