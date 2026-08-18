package com.universe.novel.infrastructure.persistence.chapter;

import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.Slug;

import org.springframework.stereotype.Component;

import java.util.ConcurrentModificationException;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

@Component
public class ChapterPersistenceAdapter implements ChapterRepositoryPort {

	private final SpringDataChapterJpaRepository repository;

	public ChapterPersistenceAdapter(SpringDataChapterJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public Optional<Chapter> findById(UUID id) {
		if (id == null) {
			return Optional.empty();
		}

		return repository.findById(id.toString()).map(this::toDomain);
	}

	@Override
	public Optional<Chapter> findBySlug(Slug slug) {
		if (slug == null) {
			return Optional.empty();
		}

		return repository.findBySlug(slug.value()).map(this::toDomain);
	}

	@Override
	public boolean existsBySlug(Slug slug) {
		if (slug == null) {
			return false;
		}

		return repository.existsBySlug(slug.value());
	}
	
	@Override
	public List<Chapter> findAllByVolumeIdOrderBySortOrder(
	        UUID volumeId
	) {
	    if (volumeId == null) {
	        return List.of();
	    }

	    return repository
	            .findAllByVolumeIdOrderBySortOrderAsc(
	                    volumeId.toString()
	            )
	            .stream()
	            .map(
	                    this::toDomain
	            )
	            .toList();
	}

	@Override
	public boolean existsByVolumeIdAndSortOrder(UUID volumeId, int sortOrder) {
		if (volumeId == null || sortOrder < 1) {

			return false;
		}

		return repository.existsByVolumeIdAndSortOrder(volumeId.toString(), sortOrder);
	}

	@Override
	public boolean existsPublishedByVolumeId(UUID volumeId) {
		if (volumeId == null) {
			return false;
		}

		return repository.existsByVolumeIdAndStatus(volumeId.toString(), ChapterStatus.PUBLISHED.name());
	}

	@Override
	public Chapter save(Chapter chapter, long expectedAggregateVersion) {
		if (chapter == null) {
			throw new IllegalArgumentException("Chapter không được để trống.");
		}

		if (expectedAggregateVersion < 0) {
			throw new IllegalArgumentException("Expected aggregate version không hợp lệ.");
		}

		String chapterId = chapter.getId().toString();

		Optional<ChapterJpaEntity> existingEntity = repository.findById(chapterId);

		ChapterJpaEntity entity;

		if (expectedAggregateVersion == 0) {

			if (existingEntity.isPresent()) {
				throw new ConcurrentModificationException("Chapter đã tồn tại hoặc đã được tạo bởi tiến trình khác.");
			}

			if (chapter.getAggregateVersion() != 1) {
				throw new IllegalArgumentException("Chapter mới phải có aggregateVersion = 1.");
			}

			entity = new ChapterJpaEntity();

		} else {

			entity = existingEntity
					.orElseThrow(() -> new ConcurrentModificationException("Chapter không còn tồn tại."));

			if (entity.getAggregateVersion() != expectedAggregateVersion) {

				throw new ConcurrentModificationException("Chapter đã được cập nhật bởi tiến trình khác.");
			}

			if (chapter.getAggregateVersion() <= expectedAggregateVersion) {

				throw new IllegalArgumentException("Chapter phải tăng aggregateVersion trước khi lưu.");
			}
		}

		mapToEntity(chapter, entity);

		ChapterJpaEntity savedEntity = repository.save(entity);

		return toDomain(savedEntity);
	}

	private void mapToEntity(Chapter chapter, ChapterJpaEntity entity) {
		entity.setId(chapter.getId().toString());

		entity.setVolumeId(chapter.getVolumeId().toString());

		entity.setChapterNumber(chapter.getChapterNumber());

		entity.setSortOrder(chapter.getSortOrder());

		entity.setTitle(chapter.getTitle());

		entity.setSlug(chapter.getSlug().value());

		entity.setSummary(chapter.getSummary());

		entity.setContent(chapter.getContent());

		entity.setStatus(chapter.getStatus().name());

		entity.setCreatedBy(chapter.getCreatedBy().toString());

		entity.setUpdatedBy(chapter.getUpdatedBy().toString());

		entity.setPublishedBy(toNullableString(chapter.getPublishedBy()));

		entity.setArchivedBy(toNullableString(chapter.getArchivedBy()));

		entity.setAggregateVersion(chapter.getAggregateVersion());

		entity.setContentVersion(chapter.getContentVersion());

		entity.setCreatedAt(chapter.getCreatedAt());

		entity.setUpdatedAt(chapter.getUpdatedAt());

		entity.setPublishedAt(chapter.getPublishedAt());

		entity.setArchivedAt(chapter.getArchivedAt());
	}

	private Chapter toDomain(ChapterJpaEntity entity) {
		return Chapter.rehydrate(UUID.fromString(entity.getId()), UUID.fromString(entity.getVolumeId()),
				entity.getChapterNumber(), entity.getSortOrder(), entity.getTitle(), new Slug(entity.getSlug()),
				entity.getSummary(), entity.getContent(), ChapterStatus.valueOf(entity.getStatus()),
				UUID.fromString(entity.getCreatedBy()), UUID.fromString(entity.getUpdatedBy()),
				toNullableUuid(entity.getPublishedBy()), toNullableUuid(entity.getArchivedBy()), entity.getCreatedAt(),
				entity.getUpdatedAt(), entity.getPublishedAt(), entity.getArchivedAt(), entity.getAggregateVersion(),
				entity.getContentVersion());
	}

	private String toNullableString(UUID value) {
		return value == null ? null : value.toString();
	}

	private UUID toNullableUuid(String value) {
		return value == null || value.isBlank() ? null : UUID.fromString(value);
	}
}