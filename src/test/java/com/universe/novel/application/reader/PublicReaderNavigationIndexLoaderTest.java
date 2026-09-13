package com.universe.novel.application.reader;

import com.universe.novel.application.ports.ReaderChapterDetailQueryPort;
import com.universe.novel.contracts.dto.reader.ReaderChapterTocItemDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicReaderNavigationIndexLoaderTest {

    @Mock
    private ReaderChapterDetailQueryPort queryPort;

    @InjectMocks
    private PublicReaderNavigationIndexLoader loader;

    @Test
    void isTransactionalReadOnly() {
        try {
            var method = PublicReaderNavigationIndexLoader.class.getMethod("load");
            Transactional tx = method.getAnnotation(Transactional.class);
            assertThat(tx).isNotNull();
            assertThat(tx.readOnly()).isTrue();
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void loadsToc() {
        List<ReaderChapterTocItemDTO> expected = List.of(new ReaderChapterTocItemDTO(1, "Title", "slug"));
        when(queryPort.findAllPublishedChaptersForToc()).thenReturn(expected);

        List<ReaderChapterTocItemDTO> result = loader.load();
        assertThat(result).isEqualTo(expected);
    }
}
