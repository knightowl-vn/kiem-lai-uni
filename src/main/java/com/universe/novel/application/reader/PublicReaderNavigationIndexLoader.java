package com.universe.novel.application.reader;

import com.universe.novel.application.ports.ReaderChapterDetailQueryPort;
import com.universe.novel.contracts.dto.reader.ReaderChapterTocItemDTO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
public class PublicReaderNavigationIndexLoader {

    private final ReaderChapterDetailQueryPort queryPort;

    public PublicReaderNavigationIndexLoader(ReaderChapterDetailQueryPort queryPort) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort must not be null");
    }

    @Transactional(readOnly = true)
    public List<ReaderChapterTocItemDTO> load() {
        return queryPort.findAllPublishedChaptersForToc();
    }
}
