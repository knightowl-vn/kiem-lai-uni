package com.universe.novel.application.ports;

import com.universe.novel.application.reader.ReaderWikiLookupResult;

/**
 * Port của Novel application để tra cứu bài viết Wiki liên quan từ ngữ cảnh đọc truyện.
 */
public interface WikiContextualLookupPort {

    /**
     * Tra cứu bài viết Wiki theo tiêu đề từ khóa đã chuẩn hóa.
     *
     * @param query từ khóa cần tra cứu
     * @return kết quả tra cứu thuộc sở hữu của Novel module
     */
    ReaderWikiLookupResult lookup(String query);
}