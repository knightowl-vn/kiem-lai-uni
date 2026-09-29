package com.universe.media.infrastructure.storage;

import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.domain.StorageProviderId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultStorageProviderRegistryTest {

    @Test
    @DisplayName("resolves registered providers correctly")
    void shouldResolveRegisteredProviders() {
        BinaryStoragePort localPort = mock(BinaryStoragePort.class);
        when(localPort.providerId()).thenReturn(StorageProviderId.of("local"));

        BinaryStoragePort r2Port = mock(BinaryStoragePort.class);
        when(r2Port.providerId()).thenReturn(StorageProviderId.of("r2"));

        BinaryStoragePort cloudinaryPort = mock(BinaryStoragePort.class);
        when(cloudinaryPort.providerId()).thenReturn(StorageProviderId.of("cloudinary"));

        DefaultStorageProviderRegistry registry = new DefaultStorageProviderRegistry(
                List.of(localPort, r2Port, cloudinaryPort)
        );

        assertThat(registry.resolve(StorageProviderId.of("local"))).isSameAs(localPort);
        assertThat(registry.resolve(StorageProviderId.of("r2"))).isSameAs(r2Port);
        assertThat(registry.resolve(StorageProviderId.of("cloudinary"))).isSameAs(cloudinaryPort);
        assertThat(registry.registeredProviders()).hasSize(3);
    }

    @Test
    @DisplayName("throws StorageException when provider is unconfigured or null")
    void shouldThrowWhenProviderNotFoundOrNull() {
        BinaryStoragePort localPort = mock(BinaryStoragePort.class);
        when(localPort.providerId()).thenReturn(StorageProviderId.of("local"));

        DefaultStorageProviderRegistry registry = new DefaultStorageProviderRegistry(List.of(localPort));

        assertThatThrownBy(() -> registry.resolve(StorageProviderId.of("s3")))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Unsupported or unconfigured storage provider: s3");

        assertThatThrownBy(() -> registry.resolve(null))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("StorageProviderId cannot be null.");
    }

    @Test
    @DisplayName("rejects duplicate provider registrations with IllegalStateException")
    void shouldRejectDuplicateProviders() {
        BinaryStoragePort port1 = mock(BinaryStoragePort.class);
        when(port1.providerId()).thenReturn(StorageProviderId.of("local"));

        BinaryStoragePort port2 = mock(BinaryStoragePort.class);
        when(port2.providerId()).thenReturn(StorageProviderId.of("local"));

        assertThatThrownBy(() -> new DefaultStorageProviderRegistry(List.of(port1, port2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate BinaryStoragePort registration detected for provider ID: local");
    }
}
