package com.lynra.kafkatower.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.config.annotation.ResourceChainRegistration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebConfigTest {

    @InjectMocks
    private WebConfig webConfig;

    @Mock
    private ResourceHandlerRegistry registry;

    @Mock
    private ResourceHandlerRegistration registration;

    @Mock
    private ResourceChainRegistration chainRegistration;

    @BeforeEach
    void setUp() {
        lenient().when(registry.addResourceHandler(anyString())).thenReturn(registration);
        lenient().when(registration.addResourceLocations(any(String[].class))).thenReturn(registration);
        lenient().when(registration.resourceChain(anyBoolean())).thenReturn(chainRegistration);
    }

    @Test
    void testWebConfigIsConfiguration() {
        assertNotNull(webConfig);
    }

    @Test
    void testAddResourceHandlers() {
        assertDoesNotThrow(() -> webConfig.addResourceHandlers(registry));
    }

    @Test
    void testAddResourceHandlersDoesNotThrow() {
        assertDoesNotThrow(() -> webConfig.addResourceHandlers(registry));
        verify(registry, times(1)).addResourceHandler("/**");
    }

    @Test
    void testWebConfigNotNull() {
        WebConfig config = new WebConfig();
        assertNotNull(config);
    }
}
