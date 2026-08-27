package com.universe.novel.application.chapter.reference;

import com.universe.novel.application.ports.ChapterWikiReferenceRepositoryPort;
import com.universe.novel.domain.reference.ChapterWikiReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

@Service
@Transactional
public class RemoveChapterWikiReferenceUseCase {

    private final ChapterWikiReferenceRepositoryPort referenceRepositoryPort;

    public RemoveChapterWikiReferenceUseCase(ChapterWikiReferenceRepositoryPort referenceRepositoryPort) {
        this.referenceRepositoryPort = Objects.requireNonNull(
                referenceRepositoryPort,
                "ChapterWikiReferenceRepositoryPort không được để trống."
        );
    }

    public boolean execute(RemoveChapterWikiReferenceCommand command) {
        Objects.requireNonNull(command, "Command không được để trống.");

        Optional<ChapterWikiReference> referenceOpt = referenceRepositoryPort.findById(command.referenceId());
        if (referenceOpt.isEmpty()) {
            return false;
        }

        ChapterWikiReference reference = referenceOpt.get();
        if (command.chapterId() != null && !Objects.equals(command.chapterId(), reference.getChapterId())) {
            return false;
        }

        referenceRepositoryPort.delete(reference);
        return true;
    }
}
