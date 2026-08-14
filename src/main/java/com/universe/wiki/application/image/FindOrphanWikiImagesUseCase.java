package com.universe.wiki.application.image;

import com.universe.wiki.application.ports
        .WikiImageRepositoryPort;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
public class FindOrphanWikiImagesUseCase {

    private final WikiImageRepositoryPort
            imageRepositoryPort;

    public FindOrphanWikiImagesUseCase(
            WikiImageRepositoryPort imageRepositoryPort
    ) {
        this.imageRepositoryPort =
                Objects.requireNonNull(
                        imageRepositoryPort,
                        "WikiImageRepositoryPort "
                                + "không được để trống."
                );
    }

    @Transactional(readOnly = true)
    public List<WikiImageAsset> execute() {
        return imageRepositoryPort
                .findOrphanImages();
    }
}