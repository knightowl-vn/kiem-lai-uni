package com.universe.wiki.application.article.alias;

import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.exceptions.WikiArticleNotFoundException;
import com.universe.wiki.application.ports.WikiArticleAliasRepositoryPort;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.contracts.dto.WikiArticleAliasDTO;
import com.universe.wiki.domain.article.WikiAliasNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class AddWikiArticleAliasUseCase {

    private static final int MIN_ALIAS_LENGTH = 1;
    private static final int MAX_ALIAS_LENGTH = 200;

    private final WikiArticleRepositoryPort articleRepositoryPort;
    private final WikiArticleAliasRepositoryPort aliasRepositoryPort;
    private final IdGeneratorPort idGeneratorPort;
    private final ClockPort clockPort;

    public AddWikiArticleAliasUseCase(
            WikiArticleRepositoryPort articleRepositoryPort,
            WikiArticleAliasRepositoryPort aliasRepositoryPort,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort
    ) {
        this.articleRepositoryPort = articleRepositoryPort;
        this.aliasRepositoryPort = aliasRepositoryPort;
        this.idGeneratorPort = idGeneratorPort;
        this.clockPort = clockPort;
    }

    @Transactional
    public WikiArticleAliasDTO execute(AddWikiArticleAliasCommand command) {
        Objects.requireNonNull(command, "Add wiki article alias command không được để trống.");
        UUID articleId = Objects.requireNonNull(command.articleId(), "Article ID không được để trống.");

        articleRepositoryPort.findById(articleId)
                .orElseThrow(() -> new WikiArticleNotFoundException(articleId));

        String cleanedAlias = WikiAliasNormalizer.cleanDisplayAlias(command.alias());
        if (cleanedAlias.length() < MIN_ALIAS_LENGTH || cleanedAlias.length() > MAX_ALIAS_LENGTH) {
            throw new IllegalArgumentException(
                    "Danh xưng/biệt danh phải có độ dài từ " + MIN_ALIAS_LENGTH + " đến " + MAX_ALIAS_LENGTH + " ký tự."
            );
        }

        String normalizedAlias = WikiAliasNormalizer.normalize(cleanedAlias);

        Optional<WikiArticleAliasDTO> existing = aliasRepositoryPort
                .findByArticleIdAndNormalizedAlias(articleId, normalizedAlias);
        if (existing.isPresent()) {
            return existing.get();
        }

        UUID aliasId = idGeneratorPort.generate();
        Instant now = clockPort.now();

        aliasRepositoryPort.save(aliasId, articleId, cleanedAlias, normalizedAlias, now);

        return new WikiArticleAliasDTO(aliasId, articleId, cleanedAlias, normalizedAlias, now);
    }
}