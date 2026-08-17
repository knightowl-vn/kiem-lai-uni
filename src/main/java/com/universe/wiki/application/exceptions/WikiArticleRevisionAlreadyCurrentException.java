package com.universe.wiki.application.exceptions;

public class WikiArticleRevisionAlreadyCurrentException
        extends RuntimeException {

    public WikiArticleRevisionAlreadyCurrentException(
            long contentVersion
    ) {
        super(
                "Phiên bản nội dung v"
                        + contentVersion
                        + " đang được bài viết sử dụng. "
                        + "Chỉ có thể khôi phục một phiên bản nội dung khác."
        );
    }
}