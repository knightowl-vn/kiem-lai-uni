package com.universe.shared.configuration;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CloudinaryConfig {

    @Bean
    public Cloudinary cloudinary(
            @Value("${cloudinary.cloud-name:${cloudinary.cloud_name:}}") String cloudName,
            @Value("${cloudinary.api-key:${cloudinary.api_key:}}") String apiKey,
            @Value("${cloudinary.api-secret:${cloudinary.api_secret:}}") String apiSecret
    ) {
        return new Cloudinary(
                ObjectUtils.asMap(
                        "cloud_name", cloudName,
                        "api_key", apiKey,
                        "api_secret", apiSecret,
                        "secure", true
                )
        );
    }
}