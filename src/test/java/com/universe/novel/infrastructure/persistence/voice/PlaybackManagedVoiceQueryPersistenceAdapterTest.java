package com.universe.novel.infrastructure.persistence.voice;

import com.universe.novel.application.ports.PlaybackManagedVoiceQueryPort.PlaybackManagedVoice;
import com.universe.novel.domain.narration.ManagedVoiceStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaybackManagedVoiceQueryPersistenceAdapterTest {

    private static final UUID VOICE_ID = UUID.fromString("a1000000-0000-0000-0000-000000000001");

    @Mock
    private SpringDataManagedVoiceJpaRepository repository;

    @Mock
    private PlaybackManagedVoiceProjection projection;

    @Test
    void mapsOnlyPlaybackFieldsAndTrimsExplicitVoiceKey() {
        givenProjection("reader-voice", "ACTIVE", 7L);
        when(repository.findPlaybackVoiceByVoiceKey("reader-voice")).thenReturn(Optional.of(projection));

        PlaybackManagedVoice result = new PlaybackManagedVoiceQueryPersistenceAdapter(repository)
                .findByVoiceKey("  reader-voice  ")
                .orElseThrow();

        assertThat(result).isEqualTo(new PlaybackManagedVoice(
                VOICE_ID, "reader-voice", ManagedVoiceStatus.ACTIVE, 7L
        ));
        verify(repository).findPlaybackVoiceByVoiceKey("reader-voice");
    }

    @Test
    void requestsOneDeterministicPreferredActiveVoice() {
        givenProjection("default-voice", "ACTIVE", 9L);
        when(repository.findPreferredPlaybackVoiceByStatus("ACTIVE")).thenReturn(Optional.of(projection));

        PlaybackManagedVoice result = new PlaybackManagedVoiceQueryPersistenceAdapter(repository)
                .findPreferredActiveVoice()
                .orElseThrow();

        assertThat(result.voiceKey()).isEqualTo("default-voice");
        assertThat(result.isActive()).isTrue();
        verify(repository).findPreferredPlaybackVoiceByStatus("ACTIVE");
    }

    @Test
    void repositoryQueriesAreLightweightAndPreferredSelectionIsStable() throws Exception {
        Method explicit = SpringDataManagedVoiceJpaRepository.class.getMethod(
                "findPlaybackVoiceByVoiceKey", String.class
        );
        Method preferred = SpringDataManagedVoiceJpaRepository.class.getMethod(
                "findPreferredPlaybackVoiceByStatus", String.class
        );
        String explicitQuery = explicit.getAnnotation(Query.class).value();
        String preferredQuery = preferred.getAnnotation(Query.class).value();

        assertThat(explicitQuery).contains(
                "v.id AS id",
                "v.voiceKey AS voiceKey",
                "v.status AS status",
                "v.synthesisRevision AS synthesisRevision",
                "WHERE v.voiceKey = :voiceKey"
        );
        assertThat(explicitQuery).doesNotContain("providerVoiceId", "displayName");
        assertThat(preferredQuery).contains(
                "where v.status = :status",
                "case when v.is_default = true then 0 else 1 end asc",
                "v.display_order asc",
                "v.created_at asc",
                "v.id asc",
                "limit 1"
        );
    }

    private void givenProjection(String voiceKey, String status, long synthesisRevision) {
        when(projection.getId()).thenReturn(VOICE_ID.toString());
        when(projection.getVoiceKey()).thenReturn(voiceKey);
        when(projection.getStatus()).thenReturn(status);
        when(projection.getSynthesisRevision()).thenReturn(synthesisRevision);
    }
}
