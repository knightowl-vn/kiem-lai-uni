package com.universe.novel.application.profile;

import com.universe.novel.application.ports.NovelProfileRepositoryPort;
import com.universe.novel.contracts.dto.profile.NovelProfileDTO;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
@Transactional(readOnly = true)
public class GetNovelProfileUseCase {

    private static final String DEFAULT_NOVEL_SLUG =
            "kiem-lai";

    private final NovelProfileRepositoryPort
            novelProfileRepositoryPort;

    public GetNovelProfileUseCase(
            NovelProfileRepositoryPort novelProfileRepositoryPort
    ) {
        this.novelProfileRepositoryPort =
                Objects.requireNonNull(
                        novelProfileRepositoryPort,
                        "NovelProfileRepositoryPort không được để trống."
                );
    }

    public NovelProfileDTO execute() {
        return novelProfileRepositoryPort
                .findBySlug(
                        DEFAULT_NOVEL_SLUG
                )
                .orElseThrow(() -> new IllegalStateException(
                        "Không tìm thấy hồ sơ tiểu thuyết mặc định: "
                                + DEFAULT_NOVEL_SLUG
                ));
    }
}
