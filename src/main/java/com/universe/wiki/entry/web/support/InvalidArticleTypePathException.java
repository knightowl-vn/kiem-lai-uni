package com.universe.wiki.entry.web.support;

public class InvalidArticleTypePathException
        extends RuntimeException {

    private static final long serialVersionUID =
            1L;


    public InvalidArticleTypePathException(
            String pathValue
    ) {

        super(
                "Article type path không hợp lệ: "
                        + pathValue
        );
    }
}