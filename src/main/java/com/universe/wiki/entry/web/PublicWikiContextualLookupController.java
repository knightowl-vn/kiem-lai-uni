package com.universe.wiki.entry.web;

import com.universe.wiki.contracts.dto.WikiContextualLookupResultDTO;
import com.universe.wiki.contracts.interfaces.WikiContextualLookupContract;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * REST controller công khai phục vụ tra cứu ngữ cảnh Wiki cho độc giả.
 */
@RestController
@RequestMapping("/wiki")
public class PublicWikiContextualLookupController {

    private final WikiContextualLookupContract wikiContextualLookupContract;

    public PublicWikiContextualLookupController(WikiContextualLookupContract wikiContextualLookupContract) {
        this.wikiContextualLookupContract = Objects.requireNonNull(
                wikiContextualLookupContract,
                "WikiContextualLookupContract không được để trống."
        );
    }

    @GetMapping("/contextual-lookup")
    public ResponseEntity<WikiContextualLookupResultDTO> lookup(
            @RequestParam(name = "q", required = false) String query
    ) {
        WikiContextualLookupResultDTO result = wikiContextualLookupContract.lookupByTitle(query);
        return ResponseEntity.ok(result);
    }
}
