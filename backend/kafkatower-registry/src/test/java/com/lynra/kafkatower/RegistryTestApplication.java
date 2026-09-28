package com.lynra.kafkatower;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Test-only {@code @SpringBootConfiguration} for this module. The real KafkaTowerApplication
 * lives in kafkatower-app, which kafkatower-registry (deliberately) does not depend on — Spring
 * Boot's test slices (e.g. {@code @DataJpaTest}) locate their configuration by searching upward
 * from the test's package for a class annotated {@code @SpringBootConfiguration}, so each module
 * that runs such a slice needs one reachable on its own test classpath. Package placement
 * (directly under {@code com.lynra.kafkatower}, matching KafkaTowerApplication's own package)
 * is what makes the upward search find this without every test needing to reference it
 * explicitly.
 */
@SpringBootApplication
public class RegistryTestApplication {
}
