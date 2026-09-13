package com.universe.novel.entry.reader;

import com.universe.novel.application.narration.GetPublicManagedVoiceCatalogQuery;
import com.universe.novel.application.narration.GetPublicManagedVoiceCatalogUseCase;
import com.universe.novel.contracts.dto.narration.PublicManagedVoiceCatalogDTO;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping("/api/novel/narration/voices")
public class PublicNovelManagedVoiceCatalogController {

    private final GetPublicManagedVoiceCatalogUseCase getCatalogUseCase;

    public PublicNovelManagedVoiceCatalogController(GetPublicManagedVoiceCatalogUseCase getCatalogUseCase) {
        this.getCatalogUseCase = Objects.requireNonNull(getCatalogUseCase, "getCatalogUseCase must not be null");
    }

    @GetMapping
    public ResponseEntity<PublicManagedVoiceCatalogDTO> getCatalog() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(getCatalogUseCase.execute(new GetPublicManagedVoiceCatalogQuery()));
    }
}
