package com.universe.wiki.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

public class WikiCoverMediaAssetDeletingException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public WikiCoverMediaAssetDeletingException(UUID mediaAssetId) {
        super(
                "WIKI_COVER_MEDIA_ASSET_DELETING",
                "Ảnh bìa đang trong quá trình xóa dọn dẹp, không thể liên kết lại: " + mediaAssetId
        );
    }
}
