package com.universe.wiki.entry.web;

import com.universe.wiki.contracts.dto.WikiContextualLookupItemDTO;
import com.universe.wiki.contracts.dto.WikiContextualLookupResultDTO;
import com.universe.wiki.contracts.interfaces.WikiContextualLookupContract;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PublicWikiContextualLookupController Unit Tests")
class PublicWikiContextualLookupControllerTest {

    @Mock
    private WikiContextualLookupContract wikiContextualLookupContract;

    private PublicWikiContextualLookupController controller;

    @BeforeEach
    void setUp() {
        controller = new PublicWikiContextualLookupController(wikiContextualLookupContract);
    }

    @Test
    @DisplayName("Khởi tạo thất bại nếu WikiContextualLookupContract null")
    void shouldThrowExceptionWhenContractIsNull() {
        assertThatThrownBy(() -> new PublicWikiContextualLookupController(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("WikiContextualLookupContract không được để trống.");
    }

    @Test
    @DisplayName("GET /wiki/contextual-lookup ủy quyền tra cứu cho WikiContextualLookupContract và trả về 200 OK")
    void shouldDelegateLookupToContractAndReturn200() {
        String query = "Trần Bình An";
        WikiContextualLookupResultDTO mockResult = new WikiContextualLookupResultDTO(
                query,
                true,
                List.of(new WikiContextualLookupItemDTO(
                        UUID.randomUUID(),
                        "Trần Bình An",
                        "CHARACTER",
                        "tran-binh-an",
                        "Nhân vật chính",
                        null
                ))
        );

        when(wikiContextualLookupContract.lookupByTitle(query)).thenReturn(mockResult);

        ResponseEntity<WikiContextualLookupResultDTO> response = controller.lookup(query);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().query()).isEqualTo(query);
        assertThat(response.getBody().hasExactMatch()).isTrue();
        assertThat(response.getBody().items()).hasSize(1);
        assertThat(response.getBody().items().get(0).title()).isEqualTo("Trần Bình An");

        verify(wikiContextualLookupContract).lookupByTitle(query);
    }

    @Test
    @DisplayName("GET /wiki/contextual-lookup với query null/trống trả về 200 OK với danh sách rỗng")
    void shouldHandleBlankQueryGracefully() {
        WikiContextualLookupResultDTO emptyResult = new WikiContextualLookupResultDTO("", false, List.of());
        when(wikiContextualLookupContract.lookupByTitle(null)).thenReturn(emptyResult);

        ResponseEntity<WikiContextualLookupResultDTO> response = controller.lookup(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().items()).isEmpty();

        verify(wikiContextualLookupContract).lookupByTitle(null);
    }

    @Test
    @DisplayName("GET /wiki/contextual-lookup với query vượt quá giới hạn 100 ký tự trả về danh sách rỗng từ contract")
    void shouldHandleOverLimitQueryFromContract() {
        String longQuery = "a".repeat(101);
        WikiContextualLookupResultDTO emptyResult = new WikiContextualLookupResultDTO(longQuery, false, List.of());
        when(wikiContextualLookupContract.lookupByTitle(longQuery)).thenReturn(emptyResult);

        ResponseEntity<WikiContextualLookupResultDTO> response = controller.lookup(longQuery);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().items()).isEmpty();

        verify(wikiContextualLookupContract).lookupByTitle(longQuery);
    }
}
