package com.universe.novel.application.reader;

import com.universe.novel.application.ports.WikiContextualLookupPort;
import com.universe.novel.application.wiki.lookup.WikiContextualLookupItem;
import com.universe.novel.application.wiki.lookup.WikiContextualLookupResult;
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
class LookupContextualWikiUseCaseTest {

    @Mock
    private WikiContextualLookupPort wikiContextualLookupPort;

    private LookupContextualWikiUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new LookupContextualWikiUseCase(wikiContextualLookupPort);
    }

    @Test
    @DisplayName("Returns empty result when rawQuery is null without calling port")
    void shouldReturnEmptyWhenQueryIsNull() {
        ReaderWikiLookupResult result = useCase.execute(null);

        assertThat(result.query()).isEmpty();
        assertThat(result.hasExactMatch()).isFalse();
        assertThat(result.items()).isEmpty();
        verify(wikiContextualLookupPort, never()).lookup(anyString());
    }

    @Test
    @DisplayName("Returns empty result when rawQuery is blank without calling port")
    void shouldReturnEmptyWhenQueryIsBlank() {
        ReaderWikiLookupResult result = useCase.execute("    ");

        assertThat(result.query()).isEmpty();
        assertThat(result.hasExactMatch()).isFalse();
        assertThat(result.items()).isEmpty();
        verify(wikiContextualLookupPort, never()).lookup(anyString());
    }

    @Test
    @DisplayName("Returns empty result when rawQuery length > 100 without calling port")
    void shouldReturnEmptyWhenQueryIsLongerThan100Characters() {
        String longQuery = "a".repeat(101);

        ReaderWikiLookupResult result = useCase.execute(longQuery);

        assertThat(result.query()).isEqualTo(longQuery);
        assertThat(result.hasExactMatch()).isFalse();
        assertThat(result.items()).isEmpty();
        verify(wikiContextualLookupPort, never()).lookup(anyString());
    }

    @Test
    @DisplayName("Delegates to port with trimmed query and maps neutral result to Reader model")
    void shouldDelegateToPortWhenQueryIsValid() {
        String rawQuery = "  Trần Bình An  ";
        String normalized = "Trần Bình An";
        UUID id = UUID.randomUUID();

        WikiContextualLookupResult portResult = new WikiContextualLookupResult(
                normalized,
                true,
                List.of(new WikiContextualLookupItem(
                        id,
                        "Trần Bình An",
                        "CHARACTER",
                        "tran-binh-an",
                        "Nhân vật chính",
                        "Tiểu Bình An"
                ))
        );

        when(wikiContextualLookupPort.lookup(normalized)).thenReturn(portResult);

        ReaderWikiLookupResult result = useCase.execute(rawQuery);

        verify(wikiContextualLookupPort).lookup(normalized);
        assertThat(result.query()).isEqualTo(normalized);
        assertThat(result.hasExactMatch()).isTrue();
        assertThat(result.items()).hasSize(1);

        ReaderWikiLookupItem item = result.items().get(0);
        assertThat(item.id()).isEqualTo(id);
        assertThat(item.title()).isEqualTo("Trần Bình An");
        assertThat(item.articleType()).isEqualTo("CHARACTER");
        assertThat(item.slug()).isEqualTo("tran-binh-an");
        assertThat(item.summary()).isEqualTo("Nhân vật chính");
        assertThat(item.matchedAlias()).isEqualTo("Tiểu Bình An");
    }
}
