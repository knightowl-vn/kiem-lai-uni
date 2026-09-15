package com.universe.novel.application.narration;

import com.universe.media.contracts.dto.*;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.novel.application.ports.*;
import com.universe.novel.application.voice.dto.ManagedVoiceDTO;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.narration.*;
import com.universe.novel.entry.admin.AdminNovelChapterNarrationCommandController;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real application chain with in-memory repository doubles; security has its separate MVC regression. */
class AdminChapterPlaybackBackfillIntegrationTest {
    @Test
    void canonicalReadySegmentsBuildAndDirectRepeatPublishesOnlyOnce() throws Exception {
        UUID chapterId = UUID.randomUUID();
        UUID voiceId = UUID.randomUUID();
        UUID outputMediaId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-09T01:00:00Z");
        var chapters = mock(ChapterRepositoryPort.class);
        var voices = mock(ManagedVoiceRepositoryPort.class);
        var segments = mock(ChapterNarrationSegmentRepositoryPort.class);
        var audios = mock(ChapterNarrationAudioRepositoryPort.class);
        var failures = mock(ChapterNarrationAudioFailureRepositoryPort.class);
        var manifests = mock(ChapterNarrationManifestRepositoryPort.class);
        var playbacks = mock(ChapterNarrationPlaybackRepositoryPort.class);
        var artifacts = mock(ChapterNarrationPlaybackArtifactRepositoryPort.class);
        var cues = mock(ChapterNarrationPlaybackCueRepositoryPort.class);
        var media = mock(MediaContract.class);
        var assembler = mock(ChapterAudioAssemblerPort.class);
        var upload = mock(UploadChapterNarrationPlaybackMediaUseCase.class);
        var cleanup = mock(RequestNarrationMediaCleanupUseCase.class);
        var generateSegment = mock(GenerateChapterNarrationAudioUseCase.class);
        var regenerateSegment = mock(RegenerateChapterNarrationAudioUseCase.class);
        var retiredCleanup = mock(CleanupCompletedChapterNarrationRetiredAudioUseCase.class);
        var clock = mock(ClockPort.class);
        var ids = mock(IdGeneratorPort.class);
        when(clock.now()).thenReturn(now);
        when(ids.generate()).thenAnswer(invocation -> UUID.randomUUID());

        Chapter chapter = mock(Chapter.class);
        when(chapter.getStatus()).thenReturn(ChapterStatus.PUBLISHED);
        when(chapter.getContentVersion()).thenReturn(1L);
        when(chapter.getChapterNumber()).thenReturn(1);
        when(chapters.findById(chapterId)).thenReturn(Optional.of(chapter));
        ManagedVoice voice = mock(ManagedVoice.class);
        when(voice.isActive()).thenReturn(true);
        when(voice.getSynthesisRevision()).thenReturn(1L);
        when(voice.getVoiceKey()).thenReturn("voice-hn");
        when(voices.findById(voiceId)).thenReturn(Optional.of(voice));
        List<ChapterNarrationSegment> current = IntStream.range(0, 3)
                .mapToObj(i -> ChapterNarrationSegment.create(UUID.randomUUID(), chapterId, i, "Đoạn " + i, now)).toList();
        List<ChapterNarrationAudio> ready = current.stream().map(segment -> ChapterNarrationAudio.create(
                UUID.randomUUID(), segment.getId(), voiceId, UUID.randomUUID(), 1L, 51840L, 48000, now)).toList();
        when(segments.findByChapterIdAndStatus(chapterId, ChapterNarrationSegmentStatus.CURRENT)).thenReturn(current);
        when(audios.findBySegmentIdInAndManagedVoiceId(any(), any())).thenReturn(ready);
        String manifestHash = NarrationManifestHasher.computeManifestHash(current.stream()
                .map(segment -> NarrationTextSegment.of(segment.getSegmentIndex(), segment.getText())).toList());
        when(manifests.findByChapterId(chapterId)).thenReturn(Optional.of(
                ChapterNarrationManifest.create(chapterId, 1L, manifestHash, now)));
        for (ChapterNarrationAudio audio : ready) {
            var source = new MediaAssetVersionSnapshotDTO(audio.getMediaAssetId(), 1, "a".repeat(64), "audio/mpeg", 4L, "segment.mp3");
            when(media.getCurrentVersionSnapshot(audio.getMediaAssetId())).thenReturn(Optional.of(source));
            when(media.openVersionContent(new MediaAssetVersionReferenceDTO(audio.getMediaAssetId(), 1, source.contentHash())))
                    .thenAnswer(invocation -> new MediaAssetVersionContentDTO(audio.getMediaAssetId(), 1,
                            source.contentHash(), "audio/mpeg", 4L, new ByteArrayInputStream(new byte[]{1, 2, 3, 4})));
        }
        when(media.getAssetDetail(outputMediaId)).thenReturn(Optional.of(new MediaAssetDetailDTO(outputMediaId,
                MediaTypeDTO.AUDIO, MediaVisibilityDTO.PUBLIC, MediaAssetStatusDTO.ACTIVE, 1, now, now,
                new MediaVersionDTO(UUID.randomUUID(), outputMediaId, 1, null, "audio/mpeg", 100L, "chapter.mp3", now))));
        var assemblyResource = mock(ChapterAudioAssemblyResource.class);
        List<ChapterAudioAssemblyCue> assembledCues = IntStream.range(0, 3).mapToObj(i ->
                new ChapterAudioAssemblyCue(i, current.get(i).getId(), i, i * 1000L, (i + 1) * 1000L)).toList();
        when(assembler.assemble(any())).thenReturn(new ChapterAudioAssemblyResult(assemblyResource, 3000L, assembledCues));
        when(upload.execute(any())).thenReturn(new UploadChapterNarrationPlaybackMediaResult(outputMediaId));

        Map<UUID, ChapterNarrationPlayback> playbackRows = new HashMap<>();
        Map<UUID, ChapterNarrationPlaybackArtifact> artifactRows = new HashMap<>();
        Map<UUID, List<ChapterNarrationPlaybackCue>> cueRows = new HashMap<>();
        when(playbacks.findByChapterIdAndManagedVoiceId(chapterId, voiceId))
                .thenAnswer(invocation -> playbackRows.values().stream().findFirst());
        when(playbacks.save(any())).thenAnswer(invocation -> {
            ChapterNarrationPlayback row = invocation.getArgument(0); playbackRows.put(row.getId(), row); return row;
        });
        when(artifacts.findById(any())).thenAnswer(invocation -> Optional.ofNullable(artifactRows.get(invocation.getArgument(0))));
        when(artifacts.insert(any())).thenAnswer(invocation -> {
            ChapterNarrationPlaybackArtifact row = invocation.getArgument(0); artifactRows.put(row.getId(), row); return row;
        });
        when(cues.findByArtifactId(any())).thenAnswer(invocation -> cueRows.getOrDefault(invocation.getArgument(0), List.of()));
        when(cues.insertAll(any())).thenAnswer(invocation -> {
            List<ChapterNarrationPlaybackCue> rows = invocation.getArgument(0); cueRows.put(rows.get(0).getArtifactId(), rows); return rows;
        });

        var snapshot = new ResolveChapterNarrationPlaybackBuildSnapshotUseCase(chapters, voices, manifests, segments, audios, media);
        var inspector = new InspectChapterNarrationPlaybackUseCase(playbacks, artifacts, cues, media, snapshot);
        var finalizer = new FinalizeChapterNarrationPlaybackUseCase(chapters, voices, manifests, segments, audios,
                playbacks, artifacts, cues, ids, clock);
        var builder = new BuildChapterNarrationPlaybackUseCase(chapters, voices, snapshot, media, assembler, upload, finalizer, cleanup, inspector);
        var generation = new GenerateChapterNarrationUseCase(chapters, voices, segments, audios, failures,
                new ChapterNarrationGenerationPlanner(), generateSegment, regenerateSegment, retiredCleanup);
        var dispatcher = new AdminNarrationGenerationDispatcher(
                Runnable::run,
                new AdminNarrationGenerationWorker(generation, builder),
                new ChapterNarrationExecutionCoordinator()
        );
        var overview = mock(GetAdminChapterNarrationOverviewUseCase.class);
        var chapterDTO = mock(ChapterDTO.class);
        var voiceDTO = mock(ManagedVoiceDTO.class);
        when(chapterDTO.status()).thenReturn("PUBLISHED");
        when(voiceDTO.status()).thenReturn("ACTIVE");
        when(overview.execute(chapterId, voiceId)).thenAnswer(invocation -> new GetAdminChapterNarrationOverviewResult(
                chapterDTO, null, List.of(voiceDTO), voiceDTO, List.of(), 3, 3, 0, 0, 0, 0, 0, 0, 0, false,
                inspector.execute(chapterId, voiceId, 1L, 1L)));
        var controller = new AdminNovelChapterNarrationCommandController(mock(AdminGenerateChapterNarrationAudioUseCase.class),
                mock(AdminRegenerateChapterNarrationAudioUseCase.class), dispatcher, overview);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        String route = "/admin/novel/chapters/" + chapterId + "/narration/generate-all";

        assertThat(inspector.execute(chapterId, voiceId, 1L, 1L).state()).isEqualTo(ChapterNarrationPlaybackState.MISSING);
        mvc.perform(post(route).param("managedVoiceId", voiceId.toString()).with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(dispatcher.getOperationState(chapterId, voiceId).status()).isEqualTo(AdminNarrationOperationStatus.SUCCEEDED);
        assertThat(dispatcher.getOperationState(chapterId, voiceId).message()).contains("đã có: 3, tạo mới: 0, cập nhật: 0");
        assertThat(inspector.execute(chapterId, voiceId, 1L, 1L).state()).isEqualTo(ChapterNarrationPlaybackState.CURRENT);

        mvc.perform(post(route).param("managedVoiceId", voiceId.toString()).with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(dispatcher.getOperationState(chapterId, voiceId).message()).isEqualTo(AdminNarrationGenerationWorker.SAFE_ALREADY_CURRENT_MESSAGE);
        assertThat(dispatcher.isRunning(chapterId, voiceId)).isFalse();
        verifyNoInteractions(generateSegment, regenerateSegment, cleanup);
        verify(assembler).assemble(any());
        verify(upload).execute(any());
        verify(artifacts).insert(any());
        verify(cues).insertAll(any());
        verify(media, times(3)).openVersionContent(any());
        assertThat(artifactRows).hasSize(1);
    }
}
