package com.universe;

import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
		"spring.jpa.hibernate.ddl-auto=validate",
		"spring.flyway.enabled=true",
		"security.remember-me.key=test-secret-key-1234567890123456",
		"security.remember-me.secure-cookie=false",
		"spring.mail.username=test@universe.local",
		"spring.mail.password=testpassword",
		"cloudinary.cloud_name=test",
		"cloudinary.api_key=test",
		"cloudinary.api_secret=test",
		"spring.security.oauth2.client.registration.google.client-id=test-google-client-id",
		"spring.security.oauth2.client.registration.google.client-secret=test-google-client-secret"
})
class KiemLaiApplicationTests {

	@DynamicPropertySource
	static void configureDatabaseProperties(DynamicPropertyRegistry registry) {
		TestDatabaseSupport.configureDynamicProperties(registry);
	}

	@Test
	void contextLoads() {
	}

}
