package com.universe.novel.entry.reader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.universe.novel.application.narration.GetPublicManagedVoiceCatalogQuery;
import com.universe.novel.application.narration.GetPublicManagedVoiceCatalogUseCase;
import com.universe.novel.contracts.dto.narration.PublicManagedVoiceCatalogDTO;
import com.universe.novel.contracts.dto.narration.PublicNarrationVoiceDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicNovelManagedVoiceCatalogControllerTest {

    @Mock
    private GetPublicManagedVoiceCatalogUseCase getCatalogUseCase;

    private PublicNovelManagedVoiceCatalogController controller;

    @BeforeEach
    void setUp() {
        controller = new PublicNovelManagedVoiceCatalogController(getCatalogUseCase);
    }

    @Test
    void returnsOnlyTheMinimalPublicContractWithoutInternalVoiceId() throws Exception {
        PublicManagedVoiceCatalogDTO catalog = new PublicManagedVoiceCatalogDTO(List.of(
                new PublicNarrationVoiceDTO("north-default", "North Default", true)
        ));
        when(getCatalogUseCase.execute(new GetPublicManagedVoiceCatalogQuery())).thenReturn(catalog);

        ResponseEntity<PublicManagedVoiceCatalogDTO> response = controller.getCatalog();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody()).isEqualTo(catalog);

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsBytes(response.getBody()));
        JsonNode voice = json.get("voices").get(0);
        assertThat(json.size()).isEqualTo(1);
        assertThat(voice.size()).isEqualTo(3);
        assertThat(voice.has("voiceKey")).isTrue();
        assertThat(voice.has("displayName")).isTrue();
        assertThat(voice.has("defaultVoice")).isTrue();
        assertThat(voice.has("id")).isFalse();
        assertThat(voice.has("managedVoiceId")).isFalse();
        assertThat(voice.has("providerVoiceId")).isFalse();
        assertThat(voice.has("synthesisRevision")).isFalse();
        verify(getCatalogUseCase).execute(new GetPublicManagedVoiceCatalogQuery());
    }
}
