package com.universe.wiki.application.ports;

import com.universe.wiki.application.image.WikiImageUploadResult;

public interface WikiImageStoragePort {

    WikiImageUploadResult upload(
            String originalFilename,
            String contentType,
            byte[] content
    );
    
    void delete(
            String publicId
    );
}