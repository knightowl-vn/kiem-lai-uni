package com.universe.novel.entry.reader;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ManagedVoiceInvalidStateException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.narration.GetPublicChapterNarrationManifestQuery;
import com.universe.novel.application.narration.GetPublicChapterNarrationManifestUseCase;
import com.universe.novel.contracts.dto.narration.PublicChapterNarrationManifestDTO;
import com.universe.novel.contracts.dto.narration.PublicNarrationSegmentDTO;
import com.universe.novel.contracts.dto.narration.PublicNarrationVoiceDTO;
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
@DisplayName("PublicNovelChapterNarrationManifestController Unit Tests (MS-04.9H.7A)")
class PublicNovelChapterNarrationManifestControllerTest {

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SEGMENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private GetPublicChapterNarrationManifestUseCase getManifestUseCase;

    private PublicNovelChapterNarrationManifestController controller;

    @BeforeEach
    void setUp() {
        controller = new PublicNovelChapterNarrationManifestController(getManifestUseCase);
    }

    @Test
    @DisplayName("1. GET /api/novel/chapters/{chapterId}/narration/manifest returns 200 OK with manifest payload")
    void shouldReturnManifestSuccessfully() {
        PublicNarrationVoiceDTO voice = new PublicNarrationVoiceDTO("kiemlai-male-01", "Minh Đức", true);
        PublicNarrationSegmentDTO segment = new PublicNarrationSegmentDTO(
                SEGMENT_ID, 0, "READY", true, "/media/assets/abc/content"
        );
        PublicChapterNarrationManifestDTO expectedManifest = new PublicChapterNarrationManifestDTO(
                CHAPTER_ID, List.of(voice), voice, List.of(segment)
        );

        when(getManifestUseCase.execute(new GetPublicChapterNarrationManifestQuery(CHAPTER_ID, null)))
                .thenReturn(expectedManifest);

        ResponseEntity<PublicChapterNarrationManifestDTO> response = controller.getManifest(CHAPTER_ID, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(expectedManifest);
        verify(getManifestUseCase).execute(new GetPublicChapterNarrationManifestQuery(CHAPTER_ID, null));
    }

    @Test
    @DisplayName("2. GET with voiceKey passes parameter to use case query")
    void shouldPassVoiceKeyToUseCase() {
        PublicNarrationVoiceDTO voice = new PublicNarrationVoiceDTO("kiemlai-female-01", "Thu Trang", false);
        PublicChapterNarrationManifestDTO expectedManifest = new PublicChapterNarrationManifestDTO(
                CHAPTER_ID, List.of(voice), voice, List.of()
        );

        when(getManifestUseCase.execute(new GetPublicChapterNarrationManifestQuery(CHAPTER_ID, "kiemlai-female-01")))
                .thenReturn(expectedManifest);

        ResponseEntity<PublicChapterNarrationManifestDTO> response = controller.getManifest(CHAPTER_ID, "kiemlai-female-01");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(expectedManifest);
        verify(getManifestUseCase).execute(new GetPublicChapterNarrationManifestQuery(CHAPTER_ID, "kiemlai-female-01"));
    }

    @Test
    @DisplayName("3. ChapterNotFoundException is translated to 404 NOT_FOUND")
    void shouldHandleChapterNotFoundException() {
        ResponseEntity<Void> response = controller.handleChapterNotFound();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("4. ManagedVoiceNotFoundException is translated to 404 NOT_FOUND")
    void shouldHandleVoiceNotFoundException() {
        ResponseEntity<Void> response = controller.handleVoiceNotFound();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("5. ManagedVoiceInvalidStateException is translated to 400 BAD_REQUEST")
    void shouldHandleVoiceInvalidStateException() {
        ResponseEntity<Void> response = controller.handleVoiceInvalidState();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
