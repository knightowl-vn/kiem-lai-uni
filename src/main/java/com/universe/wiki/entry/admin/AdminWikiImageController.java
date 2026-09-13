package com.universe.wiki.entry.admin;

import com.universe.wiki.application.image
        .UploadWikiImageUseCase;
import com.universe.wiki.application.image
        .WikiImageUploadResult;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/admin/wiki/images")
public class AdminWikiImageController {

    private final UploadWikiImageUseCase
            uploadWikiImageUseCase;

    public AdminWikiImageController(
            UploadWikiImageUseCase uploadWikiImageUseCase
    ) {
        this.uploadWikiImageUseCase =
                uploadWikiImageUseCase;
    }

    @PostMapping(
            consumes =
                    MediaType.MULTIPART_FORM_DATA_VALUE,
            produces =
                    MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> upload(
            @RequestParam("file")
            MultipartFile file
    ) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity
                    .badRequest()
                    .body(
                            Map.of(
                                    "message",
                                    "Vui lòng chọn một ảnh Wiki."
                            )
                    );
        }

        try (InputStream inputStream = file.getInputStream()) {
            WikiImageUploadResult result =
                    uploadWikiImageUseCase.execute(
                            inputStream,
                            file.getSize(),
                            file.getContentType(),
                            file.getOriginalFilename()
                    );

            Map<String, Object> response =
                    new HashMap<>();
            response.put(
                    "url",
                    result.url()
            );
            response.put(
                    "publicId",
                    result.publicId()
            );

            return ResponseEntity.ok(
                    response
            );

        } catch (
                IllegalArgumentException exception
        ) {
            return ResponseEntity
                    .badRequest()
                    .body(
                            Map.of(
                                    "message",
                                    exception.getMessage()
                            )
                    );

        } catch (IOException exception) {
            return ResponseEntity
                    .status(
                            HttpStatus.INTERNAL_SERVER_ERROR
                    )
                    .body(
                            Map.of(
                                    "message",
                                    "Không thể đọc file ảnh."
                            )
                    );

        } catch (IllegalStateException exception) {
            return ResponseEntity
                    .status(
                            HttpStatus.INTERNAL_SERVER_ERROR
                    )
                    .body(
                            Map.of(
                                    "message",
                                    exception.getMessage()
                            )
                    );
        }
    }
}