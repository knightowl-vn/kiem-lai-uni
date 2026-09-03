package com.universe.wiki.infrastructure.image;

import com.cloudinary.Cloudinary;
import com.cloudinary.Uploader;

import com.universe.wiki.application.ports.LegacyWikiImageStoragePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions
        .assertThat;
import static org.assertj.core.api.Assertions
        .assertThatCode;
import static org.assertj.core.api.Assertions
        .assertThatThrownBy;

import static org.mockito.ArgumentMatchers
        .anyMap;

import static org.mockito.Mockito
        .lenient;
import static org.mockito.Mockito
        .verify;

import static org.mockito.Mockito
        .when;


@ExtendWith(MockitoExtension.class)
class CloudinaryWikiImageStorageAdapterTest {

    @Mock
    private Cloudinary
            cloudinary;

    @Mock
    private Uploader
            uploader;

    private CloudinaryWikiImageStorageAdapter
            adapter;


    @BeforeEach
    void setUp() {

        lenient().when(
                cloudinary.uploader()
        ).thenReturn(
                uploader
        );

        adapter =
                new CloudinaryWikiImageStorageAdapter(
                        cloudinary
                );
    }


    @Test
    @DisplayName(
            "Xem ảnh Cloudinary không còn tồn tại "
                    + "là cleanup thành công"
    )
    void shouldAcceptNotFoundWhenDeletingImage()
            throws Exception {

        String publicId =
                "wiki-test-image";


        when(
                uploader.destroy(
                        org.mockito.ArgumentMatchers
                                .eq(publicId),
                        anyMap()
                )
        ).thenReturn(
                Map.of(
                        "result",
                        "not found"
                )
        );


        assertThatCode(
                () ->
                        adapter.delete(
                                publicId
                        )
        ).doesNotThrowAnyException();


        verify(
                uploader
        ).destroy(
                org.mockito.ArgumentMatchers
                        .eq(publicId),
                anyMap()
        );
    }
    
    @Test
    @DisplayName(
            "Xóa ảnh Cloudinary thành công khi result là ok"
    )
    void shouldDeleteImageWhenCloudinaryReturnsOk()
            throws Exception {

        String publicId =
                "wiki-test-image";


        when(
                uploader.destroy(
                        org.mockito.ArgumentMatchers
                                .eq(publicId),
                        anyMap()
                )
        ).thenReturn(
                Map.of(
                        "result",
                        "ok"
                )
        );


        assertThatCode(
                () ->
                        adapter.delete(
                                publicId
                        )
        ).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("adapter implements LegacyWikiImageStoragePort")
    void shouldImplementLegacyWikiImageStoragePort() {
        assertThat(adapter).isInstanceOf(LegacyWikiImageStoragePort.class);
    }

    @Test
    @DisplayName("rejects null or blank publicId")
    void shouldRejectNullOrBlankPublicId() {
        assertThatThrownBy(() -> adapter.delete(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.delete("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}