package com.universe.wiki.application.image;

public interface WikiImageStoragePort {

    WikiImageUploadResult upload(
            String originalFilename,
            String contentType,
            byte[] content
    );
}