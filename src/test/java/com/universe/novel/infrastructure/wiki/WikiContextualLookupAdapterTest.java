package com.universe.novel.infrastructure.wiki;

import com.universe.novel.application.reader.ReaderWikiLookupItem;
import com.universe.novel.application.reader.ReaderWikiLookupResult;
import com.universe.wiki.contracts.dto.WikiContextualLookupItemDTO;
import com.universe.wiki.contracts.dto.WikiContextualLookupResultDTO;
import com.universe.wiki.contracts.interfaces.WikiContextualLookupContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WikiContextualLookupAdapterTest {

    @Mock
    private WikiContextualLookupContract wikiContextualLookupContract;

    private WikiContextualLookupAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new WikiContextualLookupAdapter(wikiContextualLookupContract);
    }

    @Test
    @DisplayName("Returns empty result when query is null or blank")
    void shouldReturnEmptyWhenQueryIsBlank() {
        assertThat(adapter.lookup(null).items()).isEmpty();
        assertThat(adapter.lookup("   ").items()).isEmpty();
        verify(wikiContextualLookupContract, never()).lookupByTitle(anyString());
    }

    @Test
    @DisplayName("Maps Wiki contract DTOs into Novel-owned models accurately")
    void shouldMapWikiContractDtoToNovelModel() {
        UUID id = UUID.randomUUID();
        WikiContextualLookupItemDTO itemDTO = new WikiContextualLookupItemDTO(
                id,
                "Trần Bình An",
                "CHARACTER",
                "tran-binh-an",
                "Nhân vật chính của Kiếm Lai",
                "Tiểu Phu Tử"
        );
        WikiContextualLookupResultDTO resultDTO = new WikiContextualLookupResultDTO(
                "Tiểu Phu Tử",
                true,
                List.of(itemDTO)
        );

        when(wikiContextualLookupContract.lookupByTitle("Tiểu Phu Tử")).thenReturn(resultDTO);

        ReaderWikiLookupResult result = adapter.lookup("Tiểu Phu Tử");

        assertThat(result.query()).isEqualTo("Tiểu Phu Tử");
        assertThat(result.hasExactMatch()).isTrue();
        assertThat(result.items()).hasSize(1);

        ReaderWikiLookupItem item = result.items().get(0);
        assertThat(item.id()).isEqualTo(id);
        assertThat(item.title()).isEqualTo("Trần Bình An");
        assertThat(item.articleType()).isEqualTo("CHARACTER");
        assertThat(item.slug()).isEqualTo("tran-binh-an");
        assertThat(item.summary()).isEqualTo("Nhân vật chính của Kiếm Lai");
        assertThat(item.matchedAlias()).isEqualTo("Tiểu Phu Tử");

        verify(wikiContextualLookupContract).lookupByTitle("Tiểu Phu Tử");
    }

    @Test
    @DisplayName("Handles null DTO result from contract gracefully")
    void shouldHandleNullContractResultGracefully() {
        when(wikiContextualLookupContract.lookupByTitle("non-existent")).thenReturn(null);

        ReaderWikiLookupResult result = adapter.lookup("non-existent");

        assertThat(result.query()).isEqualTo("non-existent");
        assertThat(result.hasExactMatch()).isFalse();
        assertThat(result.items()).isEmpty();
    }
}