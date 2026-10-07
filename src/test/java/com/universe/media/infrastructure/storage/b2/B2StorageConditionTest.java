package com.universe.media.infrastructure.storage.b2;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class B2StorageConditionTest {

    private final B2StorageCondition condition = new B2StorageCondition();

    @Test
    @DisplayName("matches returns true when all required B2 properties are present and non-blank")
    void shouldMatchWhenAllPropertiesPresent() {
        ConditionContext context = mock(ConditionContext.class);
        Environment env = mock(Environment.class);
        AnnotatedTypeMetadata metadata = mock(AnnotatedTypeMetadata.class);

        when(context.getEnvironment()).thenReturn(env);
        when(env.getProperty("media.storage.b2.bucket")).thenReturn("my-b2-bucket");
        when(env.getProperty("media.storage.b2.endpoint")).thenReturn("https://s3.us-east-005.backblazeb2.com");
        when(env.getProperty("media.storage.b2.access-key-id")).thenReturn("key-id-123");
        when(env.getProperty("media.storage.b2.secret-access-key")).thenReturn("secret-key-456");
        when(env.getProperty("media.storage.b2.region")).thenReturn("us-east-005");

        assertThat(condition.matches(context, metadata)).isTrue();
    }

    @Test
    @DisplayName("matches returns false when any required B2 property is null or blank")
    void shouldNotMatchWhenAnyPropertyMissingOrBlank() {
        ConditionContext context = mock(ConditionContext.class);
        Environment env = mock(Environment.class);
        AnnotatedTypeMetadata metadata = mock(AnnotatedTypeMetadata.class);

        when(context.getEnvironment()).thenReturn(env);

        // All present baseline
        when(env.getProperty("media.storage.b2.bucket")).thenReturn("my-b2-bucket");
        when(env.getProperty("media.storage.b2.endpoint")).thenReturn("https://s3.us-east-005.backblazeb2.com");
        when(env.getProperty("media.storage.b2.access-key-id")).thenReturn("key-id-123");
        when(env.getProperty("media.storage.b2.secret-access-key")).thenReturn("secret-key-456");
        when(env.getProperty("media.storage.b2.region")).thenReturn("us-east-005");

        // Missing bucket
        when(env.getProperty("media.storage.b2.bucket")).thenReturn(null);
        assertThat(condition.matches(context, metadata)).isFalse();

        // Blank bucket
        when(env.getProperty("media.storage.b2.bucket")).thenReturn("   ");
        assertThat(condition.matches(context, metadata)).isFalse();

        // Reset bucket, missing endpoint
        when(env.getProperty("media.storage.b2.bucket")).thenReturn("my-b2-bucket");
        when(env.getProperty("media.storage.b2.endpoint")).thenReturn("");
        assertThat(condition.matches(context, metadata)).isFalse();

        // Reset endpoint, missing access key id
        when(env.getProperty("media.storage.b2.endpoint")).thenReturn("https://s3.us-east-005.backblazeb2.com");
        when(env.getProperty("media.storage.b2.access-key-id")).thenReturn(null);
        assertThat(condition.matches(context, metadata)).isFalse();

        // Reset access key id, blank secret access key
        when(env.getProperty("media.storage.b2.access-key-id")).thenReturn("key-id-123");
        when(env.getProperty("media.storage.b2.secret-access-key")).thenReturn("  ");
        assertThat(condition.matches(context, metadata)).isFalse();

        // Reset secret access key, missing region
        when(env.getProperty("media.storage.b2.secret-access-key")).thenReturn("secret-key-456");
        when(env.getProperty("media.storage.b2.region")).thenReturn(null);
        assertThat(condition.matches(context, metadata)).isFalse();

        // Blank region
        when(env.getProperty("media.storage.b2.region")).thenReturn("   ");
        assertThat(condition.matches(context, metadata)).isFalse();
    }
}
