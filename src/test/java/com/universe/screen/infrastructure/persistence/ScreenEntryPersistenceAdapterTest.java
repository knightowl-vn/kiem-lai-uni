package com.universe.screen.infrastructure.persistence;

import com.universe.screen.application.exceptions.ScreenEntrySlugAlreadyExistsException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenPublicationStatus;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScreenEntryPersistenceAdapter Unit Tests")
class ScreenEntryPersistenceAdapterTest {

    @Mock
    private SpringDataScreenEntryJpaRepository repository;

    @Mock
    private SpringDataScreenTitleJpaRepository titleRepository;

    private ScreenEntryPersistenceAdapter adapter;

    private final UUID entryId = UUID.randomUUID();
    private final UUID titleId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-06T10:00:00Z");

    @BeforeEach
    void setUp() {
        adapter = new ScreenEntryPersistenceAdapter(repository, titleRepository);
    }

    @Test
    @DisplayName("PersistenceAdapter: save persists entry entity and maps to domain")
    void shouldSaveAndMapToDomain() {
        ScreenEntry entry = ScreenEntry.createDraft(
                entryId, titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Episode 1", now
        );
        ScreenEntryJpaEntity entity = ScreenEntryPersistenceMapper.toEntity(entry);

        when(repository.findById(entryId.toString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ScreenEntryJpaEntity.class))).thenReturn(entity);

        ScreenEntry saved = adapter.save(entry);

        assertThat(saved.getId()).isEqualTo(entryId);
        assertThat(saved.getTitleId()).isEqualTo(titleId);
        assertThat(saved.getSlug()).isEqualTo("ep-1");
        assertThat(saved.getTitle()).isEqualTo("Episode 1");
        verify(repository).saveAndFlush(any(ScreenEntryJpaEntity.class));
    }

    @Test
    @DisplayName("PersistenceAdapter: save translates uq_screen_entries_title_slug constraint violation to ScreenEntrySlugAlreadyExistsException")
    void shouldTranslateSlugConstraintViolationToScreenEntrySlugAlreadyExistsException() {
        ScreenEntry entry = ScreenEntry.createDraft(
                entryId, titleId, "duplicate-slug", ScreenEntryType.EPISODE, 1, "Episode 1", now
        );
        ConstraintViolationException cve = new ConstraintViolationException(
                "Duplicate entry '...-duplicate-slug' for key 'screen_entries.uq_screen_entries_title_slug'",
                null,
                "uq_screen_entries_title_slug"
        );
        DataIntegrityViolationException dive = new DataIntegrityViolationException("Constraint violation", cve);

        when(repository.findById(entryId.toString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ScreenEntryJpaEntity.class))).thenThrow(dive);

        assertThatThrownBy(() -> adapter.save(entry))
                .isInstanceOf(ScreenEntrySlugAlreadyExistsException.class)
                .hasMessageContaining("duplicate-slug")
                .hasCause(dive);
    }

    @Test
    @DisplayName("PersistenceAdapter: save translates uq_screen_entries_title_slug constraint violation on update to ScreenEntrySlugAlreadyExistsException")
    void shouldTranslateSlugConstraintViolationOnUpdateToScreenEntrySlugAlreadyExistsException() {
        ScreenEntry entry = ScreenEntry.createDraft(
                entryId, titleId, "duplicate-slug", ScreenEntryType.EPISODE, 1, "Episode 1", now
        );
        ScreenEntryJpaEntity existingEntity = ScreenEntryPersistenceMapper.toEntity(entry);

        ConstraintViolationException cve = new ConstraintViolationException(
                "Duplicate entry '...-duplicate-slug' for key 'screen_entries.uq_screen_entries_title_slug'",
                null,
                "uq_screen_entries_title_slug"
        );
        DataIntegrityViolationException dive = new DataIntegrityViolationException("Constraint violation", cve);

        when(repository.findById(entryId.toString())).thenReturn(Optional.of(existingEntity));
        when(repository.saveAndFlush(existingEntity)).thenThrow(dive);

        assertThatThrownBy(() -> adapter.save(entry))
                .isInstanceOf(ScreenEntrySlugAlreadyExistsException.class)
                .hasMessageContaining("duplicate-slug")
                .hasCause(dive);
    }

    @Test
    @DisplayName("PersistenceAdapter: save rethrows unrelated DataIntegrityViolationException")
    void shouldRethrowUnrelatedDataIntegrityViolationException() {
        ScreenEntry entry = ScreenEntry.createDraft(
                entryId, titleId, "some-slug", ScreenEntryType.EPISODE, 1, "Episode 1", now
        );
        DataIntegrityViolationException dive = new DataIntegrityViolationException("Unrelated DB failure");

        when(repository.findById(entryId.toString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ScreenEntryJpaEntity.class))).thenThrow(dive);

        assertThatThrownBy(() -> adapter.save(entry))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isSameAs(dive);
    }

    @Test
    @DisplayName("PersistenceAdapter: save does not translate different constraint whose name merely contains 'slug'")
    void shouldNotTranslateDifferentConstraintContainingSlug() {
        ScreenEntry entry = ScreenEntry.createDraft(
                entryId, titleId, "some-slug", ScreenEntryType.EPISODE, 1, "Episode 1", now
        );
        ConstraintViolationException cve = new ConstraintViolationException(
                "Check constraint failed: chk_screen_entries_slug_length",
                null,
                "chk_screen_entries_slug_length"
        );
        DataIntegrityViolationException dive = new DataIntegrityViolationException("Constraint violation", cve);

        when(repository.findById(entryId.toString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ScreenEntryJpaEntity.class))).thenThrow(dive);

        assertThatThrownBy(() -> adapter.save(entry))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isSameAs(dive);
    }

    @Test
    @DisplayName("PersistenceAdapter: findById retrieves and maps entry")
    void shouldFindById() {
        ScreenEntry entry = ScreenEntry.createDraft(
                entryId, titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Episode 1", now
        );
        ScreenEntryJpaEntity entity = ScreenEntryPersistenceMapper.toEntity(entry);

        when(repository.findById(entryId.toString())).thenReturn(Optional.of(entity));

        Optional<ScreenEntry> found = adapter.findById(entryId);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(entryId);
        assertThat(found.get().getTitle()).isEqualTo("Episode 1");
    }

    @Test
    @DisplayName("PersistenceAdapter: findByTitleId returns entries mapped to domain")
    void shouldFindByTitleId() {
        ScreenEntry entry = ScreenEntry.createDraft(
                entryId, titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Episode 1", now
        );
        ScreenEntryJpaEntity entity = ScreenEntryPersistenceMapper.toEntity(entry);

        when(repository.findByTitleId(titleId.toString())).thenReturn(List.of(entity));

        List<ScreenEntry> entries = adapter.findByTitleId(titleId);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getId()).isEqualTo(entryId);
    }

    @Test
    @DisplayName("PersistenceAdapter: findByTitleIdAndSlug normalizes slug and retrieves entry")
    void shouldFindByTitleIdAndSlug() {
        ScreenEntry entry = ScreenEntry.createDraft(
                entryId, titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Episode 1", now
        );
        ScreenEntryJpaEntity entity = ScreenEntryPersistenceMapper.toEntity(entry);

        when(repository.findByTitleIdAndSlug(titleId.toString(), "ep-1")).thenReturn(Optional.of(entity));

        Optional<ScreenEntry> found = adapter.findByTitleIdAndSlug(titleId, "  EP-1  ");

        assertThat(found).isPresent();
        assertThat(found.get().getSlug()).isEqualTo("ep-1");
    }

    @Test
    @DisplayName("PersistenceAdapter: findPublishedById filters by PUBLISHED status")
    void shouldFindPublishedById() {
        ScreenEntry entry = ScreenEntry.createDraft(
                entryId, titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Episode 1", now
        );
        entry.publish(now.plusSeconds(100));
        ScreenEntryJpaEntity entity = ScreenEntryPersistenceMapper.toEntity(entry);

        when(repository.findByIdAndStatus(entryId.toString(), "PUBLISHED")).thenReturn(Optional.of(entity));

        Optional<ScreenEntry> found = adapter.findPublishedById(entryId);

        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(ScreenPublicationStatus.PUBLISHED);
    }

    @Test
    @DisplayName("PersistenceAdapter: existsById and existsByTitleIdAndSlug delegate properly")
    void shouldCheckExistence() {
        when(repository.existsById(entryId.toString())).thenReturn(true);
        when(repository.existsByTitleIdAndSlug(titleId.toString(), "ep-1")).thenReturn(true);

        assertThat(adapter.existsById(entryId)).isTrue();
        assertThat(adapter.existsByTitleIdAndSlug(titleId, "  ep-1  ")).isTrue();
    }

    @Test
    @DisplayName("PersistenceAdapter: findEntryIdsByVideoMediaAssetIdForUpdate delegates to repository with string UUID and maps to List<UUID>")
    void shouldDelegateFindEntryIdsByVideoMediaAssetIdForUpdate() {
        UUID videoAssetId = UUID.randomUUID();
        UUID entryId1 = UUID.randomUUID();
        UUID entryId2 = UUID.randomUUID();

        when(repository.findEntryIdsByVideoMediaAssetIdForUpdate(videoAssetId.toString()))
                .thenReturn(List.of(entryId1.toString(), entryId2.toString()));

        List<UUID> result = adapter.findEntryIdsByVideoMediaAssetIdForUpdate(videoAssetId);

        assertThat(result).containsExactly(entryId1, entryId2);
        verify(repository).findEntryIdsByVideoMediaAssetIdForUpdate(videoAssetId.toString());
    }

    @Test
    @DisplayName("PersistenceAdapter: findEntryIdsByVideoMediaAssetIdForUpdate returns empty list for valid UUID with zero matching references")
    void shouldReturnEmptyListWhenValidAssetHasZeroReferences() {
        UUID unattachedAssetId = UUID.randomUUID();
        when(repository.findEntryIdsByVideoMediaAssetIdForUpdate(unattachedAssetId.toString()))
                .thenReturn(List.of());

        List<UUID> result = adapter.findEntryIdsByVideoMediaAssetIdForUpdate(unattachedAssetId);

        assertThat(result).isEmpty();
        verify(repository).findEntryIdsByVideoMediaAssetIdForUpdate(unattachedAssetId.toString());
    }

    @Test
    @DisplayName("PersistenceAdapter: findEntryIdsByVideoMediaAssetIdForUpdate fails fast with NullPointerException when assetId is null")
    void shouldFailFastWhenVideoMediaAssetIdIsNull() {
        assertThatThrownBy(() -> adapter.findEntryIdsByVideoMediaAssetIdForUpdate(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Video media asset ID cannot be null");

        verifyNoInteractions(repository);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private PlatformTransactionManager createTestTransactionManager() {
        return new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected boolean isExistingTransaction(Object transaction) {
                return TransactionSynchronizationManager.isActualTransactionActive();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
                TransactionSynchronizationManager.setActualTransactionActive(true);
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
        };
    }

    @Test
    @DisplayName("Transaction Contract: findEntryIdsByVideoMediaAssetIdForUpdate requires Propagation.MANDATORY")
    void shouldRequireMandatoryTransactionPropagation() throws NoSuchMethodException {
        Method method = ScreenEntryPersistenceAdapter.class
                .getMethod("findEntryIdsByVideoMediaAssetIdForUpdate", UUID.class);
        Transactional annotation = method.getAnnotation(Transactional.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    @Test
    @DisplayName("Transaction Proxy: invoking findEntryIdsByVideoMediaAssetIdForUpdate outside transaction throws IllegalTransactionStateException")
    void shouldThrowIllegalTransactionStateExceptionWhenInvokedOutsideTransactionThroughProxy() {
        AnnotationTransactionAttributeSource tas = new AnnotationTransactionAttributeSource();
        PlatformTransactionManager txManager = createTestTransactionManager();
        TransactionInterceptor interceptor = new TransactionInterceptor(txManager, tas);

        ProxyFactory proxyFactory = new ProxyFactory(adapter);
        proxyFactory.addAdvice(interceptor);
        ScreenEntryRepositoryPort proxiedAdapter = (ScreenEntryRepositoryPort) proxyFactory.getProxy();

        UUID videoAssetId = UUID.randomUUID();

        assertThatThrownBy(() -> proxiedAdapter.findEntryIdsByVideoMediaAssetIdForUpdate(videoAssetId))
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessageContaining("No existing transaction found for transaction marked with propagation 'mandatory'");

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("findLatestPublishedEpisodeNumbersByTitleIds returns empty map without DB interaction when input is null or empty")
    void findLatestPublishedEpisodeNumbersByTitleIds_shouldReturnEmptyMapWhenInputIsNullOrEmpty() {
        assertThat(adapter.findLatestPublishedEpisodeNumbersByTitleIds(null)).isEmpty();
        assertThat(adapter.findLatestPublishedEpisodeNumbersByTitleIds(List.of())).isEmpty();
        assertThat(adapter.findLatestPublishedEpisodeNumbersByTitleIds(Collections.singletonList(null))).isEmpty();

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("findLatestPublishedEpisodeNumbersByTitleIds maps JPA repository Object array rows to UUID -> Integer map")
    void findLatestPublishedEpisodeNumbersByTitleIds_shouldMapRepositoryResultsToUuidMap() {
        UUID titleId1 = UUID.randomUUID();
        UUID titleId2 = UUID.randomUUID();

        List<Object[]> rawRows = List.<Object[]>of(
                new Object[]{titleId1.toString(), 24},
                new Object[]{titleId2.toString(), 1}
        );

        when(repository.findLatestPublishedEpisodeNumbersByTitleIds(List.of(titleId1.toString(), titleId2.toString())))
                .thenReturn(rawRows);

        Map<UUID, Integer> latestEpisodes = adapter.findLatestPublishedEpisodeNumbersByTitleIds(List.of(titleId1, titleId2));

        assertThat(latestEpisodes)
                .hasSize(2)
                .containsEntry(titleId1, 24)
                .containsEntry(titleId2, 1);
    }

    @Test
    @DisplayName("findLatestPublishedEpisodeNumberByTitleId delegates to repository")
    void findLatestPublishedEpisodeNumberByTitleId_shouldDelegateToRepository() {
        UUID titleId = UUID.randomUUID();
        when(repository.findLatestPublishedEpisodeNumberByTitleId(titleId.toString())).thenReturn(18);

        Integer result = adapter.findLatestPublishedEpisodeNumberByTitleId(titleId);

        assertThat(result).isEqualTo(18);
        verify(repository).findLatestPublishedEpisodeNumberByTitleId(titleId.toString());

        assertThat(adapter.findLatestPublishedEpisodeNumberByTitleId(null)).isNull();
    }

    @Test
    @DisplayName("findVideoMediaAssetIdById returns empty if id is null or repository returns empty")
    void findVideoMediaAssetIdById_shouldReturnEmptyWhenNullOrNotFound() {
        assertThat(adapter.findVideoMediaAssetIdById(null)).isEmpty();

        UUID testId = UUID.randomUUID();
        when(repository.findVideoMediaAssetIdById(testId.toString())).thenReturn(Optional.empty());
        assertThat(adapter.findVideoMediaAssetIdById(testId)).isEmpty();
    }

    @Test
    @DisplayName("findVideoMediaAssetIdById returns parsed UUID when found")
    void findVideoMediaAssetIdById_shouldReturnParsedUuidWhenFound() {
        UUID testId = UUID.randomUUID();
        UUID videoAssetId = UUID.randomUUID();
        when(repository.findVideoMediaAssetIdById(testId.toString())).thenReturn(Optional.of(videoAssetId.toString()));

        assertThat(adapter.findVideoMediaAssetIdById(testId)).contains(videoAssetId);
    }

    @Test
    @DisplayName("Transaction Contract: hasOtherPublishedReference requires Propagation.MANDATORY")
    void hasOtherPublishedReference_shouldRequireMandatoryTransactionPropagation() throws NoSuchMethodException {
        Method method = ScreenEntryPersistenceAdapter.class
                .getMethod("hasOtherPublishedReference", UUID.class, UUID.class);
        Transactional annotation = method.getAnnotation(Transactional.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    @Test
    @DisplayName("hasOtherPublishedReference returns false when assetId is null or count is zero")
    void hasOtherPublishedReference_shouldReturnFalseWhenNullOrCountZero() {
        assertThat(adapter.hasOtherPublishedReference(null, UUID.randomUUID())).isFalse();

        UUID videoAssetId = UUID.randomUUID();
        UUID excludingEntryId = UUID.randomUUID();
        when(repository.countOtherPublishedReferencesExcluding(videoAssetId.toString(), excludingEntryId.toString()))
                .thenReturn(0L);

        assertThat(adapter.hasOtherPublishedReference(videoAssetId, excludingEntryId)).isFalse();
    }

    @Test
    @DisplayName("hasOtherPublishedReference returns true when count with excludingEntryId is greater than zero")
    void hasOtherPublishedReference_shouldReturnTrueWhenCountGreaterThanZeroWithExclusion() {
        UUID videoAssetId = UUID.randomUUID();
        UUID excludingEntryId = UUID.randomUUID();

        when(repository.countOtherPublishedReferencesExcluding(videoAssetId.toString(), excludingEntryId.toString()))
                .thenReturn(1L);

        assertThat(adapter.hasOtherPublishedReference(videoAssetId, excludingEntryId)).isTrue();
        verify(repository).countOtherPublishedReferencesExcluding(videoAssetId.toString(), excludingEntryId.toString());
    }

    @Test
    @DisplayName("hasOtherPublishedReference delegates to countOtherPublishedReferences without exclusion when excludingEntryId is null")
    void hasOtherPublishedReference_shouldDelegateToCountWithoutExclusionWhenNullExcludingId() {
        UUID videoAssetId = UUID.randomUUID();

        when(repository.countOtherPublishedReferences(videoAssetId.toString()))
                .thenReturn(2L);

        assertThat(adapter.hasOtherPublishedReference(videoAssetId, null)).isTrue();
        verify(repository).countOtherPublishedReferences(videoAssetId.toString());
    }

    @Test
    @DisplayName("Transaction Contract: findPublishedEntryVideoBindingsByTitleIdCurrentRead and findEntryVideoBindingsByTitleIdCurrentRead require Propagation.MANDATORY")
    void currentReadBindingMethods_shouldRequireMandatoryTransactionPropagation() throws NoSuchMethodException {
        Method pubMethod = ScreenEntryPersistenceAdapter.class
                .getMethod("findPublishedEntryVideoBindingsByTitleIdCurrentRead", UUID.class);
        Transactional pubTx = pubMethod.getAnnotation(Transactional.class);
        assertThat(pubTx).isNotNull();
        assertThat(pubTx.propagation()).isEqualTo(Propagation.MANDATORY);

        Method allMethod = ScreenEntryPersistenceAdapter.class
                .getMethod("findEntryVideoBindingsByTitleIdCurrentRead", UUID.class);
        Transactional allTx = allMethod.getAnnotation(Transactional.class);
        assertThat(allTx).isNotNull();
        assertThat(allTx.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    @Test
    @DisplayName("PersistenceAdapter: findPublishedEntryVideoBindingsByTitleIdCurrentRead maps scalar Object[] rows to EntryVideoBinding")
    void shouldMapScalarRowsForFindPublishedEntryVideoBindingsByTitleIdCurrentRead() {
        UUID entry1Id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID entry2Id = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID video1Id = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

        when(repository.findPublishedEntryVideoBindingsByTitleIdCurrentRead(titleId.toString()))
                .thenReturn(List.of(
                        new Object[]{entry1Id.toString(), video1Id.toString()},
                        new Object[]{entry2Id.toString(), null}
                ));

        List<ScreenEntryRepositoryPort.EntryVideoBinding> bindings =
                adapter.findPublishedEntryVideoBindingsByTitleIdCurrentRead(titleId);

        assertThat(bindings).containsExactly(
                new ScreenEntryRepositoryPort.EntryVideoBinding(entry1Id, video1Id),
                new ScreenEntryRepositoryPort.EntryVideoBinding(entry2Id, null)
        );
        assertThat(adapter.findPublishedEntryVideoBindingsByTitleIdCurrentRead(null)).isEmpty();
    }

    @Test
    @DisplayName("PersistenceAdapter: findEntryVideoBindingsByTitleIdCurrentRead maps scalar Object[] rows to EntryVideoBinding")
    void shouldMapScalarRowsForFindEntryVideoBindingsByTitleIdCurrentRead() {
        UUID entry1Id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID video1Id = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

        when(repository.findEntryVideoBindingsByTitleIdCurrentRead(titleId.toString()))
                .thenReturn(List.<Object[]>of(new Object[]{entry1Id.toString(), video1Id.toString()}));

        List<ScreenEntryRepositoryPort.EntryVideoBinding> bindings =
                adapter.findEntryVideoBindingsByTitleIdCurrentRead(titleId);

        assertThat(bindings).containsExactly(
                new ScreenEntryRepositoryPort.EntryVideoBinding(entry1Id, video1Id)
        );
        assertThat(adapter.findEntryVideoBindingsByTitleIdCurrentRead(null)).isEmpty();
    }
}
