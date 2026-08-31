package com.universe.novel.application.ports;

import com.universe.novel.application.wiki.lookup.WikiContextualLookupResult;

/**
 * Port của Novel application để tra cứu bài viết Wiki liên quan từ ngữ cảnh.
 */
public interface WikiContextualLookupPort {

    /**
     * Tra cứu bài viết Wiki theo từ khóa đã chuẩn hóa.
     *
     * @param query từ khóa cần tra cứu
     * @return kết quả tra cứu trung lập thuộc sở hữu của Novel module
     */
    WikiContextualLookupResult lookup(String query);
}
