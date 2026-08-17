package com.universe.wiki.infrastructure.markdown;

import com.universe.wiki.application.ports
        .WikiMarkdownImageExtractor;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Image;
import org.commonmark.node.Node;

import org.commonmark.parser.Parser;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

@Component
public class CommonMarkWikiMarkdownImageExtractor
        implements WikiMarkdownImageExtractor {

    private final Parser parser;

    public CommonMarkWikiMarkdownImageExtractor() {
        this.parser =
                Parser.builder()
                        .build();
    }

    @Override
    public Set<String> extractImageUrls(
            String markdown
    ) {
        if (
                markdown == null
                || markdown.isBlank()
        ) {
            return Set.of();
        }

        Node document =
                parser.parse(
                        markdown
                );

        Set<String> imageUrls =
                new LinkedHashSet<>();

        document.accept(
                new AbstractVisitor() {

                    @Override
                    public void visit(
                            Image image
                    ) {
                        String destination =
                                image.getDestination();

                        if (
                                destination != null
                                && !destination.isBlank()
                        ) {
                            imageUrls.add(
                                    destination.trim()
                            );
                        }

                        visitChildren(
                                image
                        );
                    }
                }
        );

        return Set.copyOf(
                imageUrls
        );
    }
}