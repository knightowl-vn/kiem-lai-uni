package com.universe.novel.entry.reader;

import com.universe.novel.application.reader.LookupContextualWikiUseCase;
import com.universe.novel.application.reader.ReaderWikiLookupItem;
import com.universe.novel.application.reader.ReaderWikiLookupResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReaderWikiLookupControllerTest {

    @Mock
    private LookupContextualWikiUseCase lookupContextualWikiUseCase;

    private ReaderWikiLookupController controller;

    @BeforeEach
    void setUp() {
        controller = new ReaderWikiLookupController(lookupContextualWikiUseCase);
    }

    @Test
    @DisplayName("Returns 200 OK with lookup result from use case")
    void shouldReturn200WithLookupResult() {
        UUID id = UUID.randomUUID();
        ReaderWikiLookupResult expectedResult = new ReaderWikiLookupResult(
                "Trần Bình An",
                true,
                List.of(new ReaderWikiLookupItem(
                        id,
                        "Trần Bình An",
                        "CHARACTER",
                        "tran-binh-an",
                        "Nhân vật chính của Kiếm Lai"
                ))
        );

        when(lookupContextualWikiUseCase.execute("Trần Bình An")).thenReturn(expectedResult);

        ResponseEntity<ReaderWikiLookupResult> response = controller.lookup("Trần Bình An");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(expectedResult);
        verify(lookupContextualWikiUseCase).execute("Trần Bình An");
    }

    @Test
    @DisplayName("Passes null query to use case and returns 200 OK with empty result")
    void shouldHandleNullQuery() {
        ReaderWikiLookupResult emptyResult = new ReaderWikiLookupResult("", false, List.of());
        when(lookupContextualWikiUseCase.execute(null)).thenReturn(emptyResult);

        ResponseEntity<ReaderWikiLookupResult> response = controller.lookup(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(emptyResult);
        verify(lookupContextualWikiUseCase).execute(null);
    }
}