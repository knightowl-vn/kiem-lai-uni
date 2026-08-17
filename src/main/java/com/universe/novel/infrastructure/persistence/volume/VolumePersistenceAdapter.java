package com.universe.novel.infrastructure.persistence.volume;

import com.universe.novel.application.ports.VolumeRepositoryPort;
import com.universe.novel.domain.Slug;
import com.universe.novel.domain.Volume;
import com.universe.novel.domain.VolumeStatus;

import org.springframework.stereotype.Component;

import java.util.ConcurrentModificationException;
import java.util.Optional;
import java.util.UUID;

@Component
public class VolumePersistenceAdapter implements VolumeRepositoryPort {

	private final SpringDataVolumeJpaRepository repository;

	public VolumePersistenceAdapter(SpringDataVolumeJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public Optional<Volume> findById(UUID id) {
		if (id == null) {
			return Optional.empty();
		}

		return repository.findById(id.toString()).map(this::toDomain);
	}

	@Override
	public Optional<Volume> findBySlug(Slug slug) {
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
	public boolean existsBySortOrder(int sortOrder) {
		if (sortOrder < 1) {
			return false;
		}

		return repository.existsBySortOrder(sortOrder);
	}

	@Override
	public Volume save(Volume volume, long expectedAggregateVersion) {
		if (volume == null) {
			throw new IllegalArgumentException("Volume không được để trống.");
		}

		if (expectedAggregateVersion < 0) {
			throw new IllegalArgumentException("Expected aggregate version không hợp lệ.");
		}

		String volumeId = volume.getId().toString();

		Optional<VolumeJpaEntity> existingEntity = repository.findById(volumeId);

		VolumeJpaEntity entity;

		if (expectedAggregateVersion == 0) {

			if (existingEntity.isPresent()) {
				throw new ConcurrentModificationException("Volume đã tồn tại hoặc đã được tạo bởi tiến trình khác.");
			}

			if (volume.getAggregateVersion() != 1) {
				throw new IllegalArgumentException("Volume mới phải có aggregateVersion = 1.");
			}

			entity = new VolumeJpaEntity();

		} else {

			entity = existingEntity.orElseThrow(() -> new ConcurrentModificationException("Volume không còn tồn tại."));

			if (entity.getAggregateVersion() != expectedAggregateVersion) {

				throw new ConcurrentModificationException("Volume đã được cập nhật bởi tiến trình khác.");
			}

			if (volume.getAggregateVersion() <= expectedAggregateVersion) {

				throw new IllegalArgumentException("Volume phải tăng aggregateVersion trước khi lưu.");
			}
		}

		mapToEntity(volume, entity);

		VolumeJpaEntity savedEntity = repository.save(entity);

		return toDomain(savedEntity);
	}

	private void mapToEntity(Volume volume, VolumeJpaEntity entity) {
		entity.setId(volume.getId().toString());

		entity.setTitle(volume.getTitle());

		entity.setSlug(volume.getSlug().value());

		entity.setDescription(volume.getDescription());

		entity.setSortOrder(volume.getSortOrder());

		entity.setStatus(volume.getStatus().name());

		entity.setCreatedBy(volume.getCreatedBy().toString());

		entity.setUpdatedBy(volume.getUpdatedBy().toString());

		entity.setPublishedBy(toNullableString(volume.getPublishedBy()));

		entity.setArchivedBy(toNullableString(volume.getArchivedBy()));

		entity.setAggregateVersion(volume.getAggregateVersion());

		entity.setCreatedAt(volume.getCreatedAt());

		entity.setUpdatedAt(volume.getUpdatedAt());

		entity.setPublishedAt(volume.getPublishedAt());

		entity.setArchivedAt(volume.getArchivedAt());
	}

	private Volume toDomain(VolumeJpaEntity entity) {
		return Volume.rehydrate(UUID.fromString(entity.getId()), entity.getTitle(), new Slug(entity.getSlug()),
				entity.getDescription(), entity.getSortOrder(), VolumeStatus.valueOf(entity.getStatus()),
				UUID.fromString(entity.getCreatedBy()), UUID.fromString(entity.getUpdatedBy()),
				toNullableUuid(entity.getPublishedBy()), toNullableUuid(entity.getArchivedBy()), entity.getCreatedAt(),
				entity.getUpdatedAt(), entity.getPublishedAt(), entity.getArchivedAt(), entity.getAggregateVersion());
	}

	private String toNullableString(UUID value) {
		return value == null ? null : value.toString();
	}

	private UUID toNullableUuid(String value) {
		return value == null || value.isBlank() ? null : UUID.fromString(value);
	}
}