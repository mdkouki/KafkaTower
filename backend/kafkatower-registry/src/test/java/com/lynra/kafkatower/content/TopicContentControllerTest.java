package com.lynra.kafkatower.content;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TopicContentControllerTest {

    @Mock
    private ContentSearchService search;
    @Mock
    private TopicSchemaRepository schemas;

    private TopicContentController controller;

    @BeforeEach
    void setUp() {
        controller = new TopicContentController(search, schemas);
    }

    @Test
    void searchRequiresClusterAndTopic() {
        var req = new ContentSearchRequest(null, "t", null, null, null, null, null, null, null, null);
        var ex = assertThrows(ResponseStatusException.class, () -> controller.search(req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void validAvroSchemaIsStored() throws Exception {
        var file = new MockMultipartFile("file", "o.avsc", "application/json",
                "{\"type\":\"record\",\"name\":\"O\",\"fields\":[{\"name\":\"id\",\"type\":\"int\"}]}".getBytes(StandardCharsets.UTF_8));
        when(schemas.save(any(TopicSchema.class))).thenAnswer(i -> i.getArgument(0));

        var info = controller.putSchema("c", "t", ContentFormat.AVRO, null, file);

        assertEquals(ContentFormat.AVRO, info.format());
        verify(schemas).save(any(TopicSchema.class));
    }

    @Test
    void invalidSchemaIsRejectedAndNotStored() {
        var file = new MockMultipartFile("file", "o.avsc", "application/json", "{nope".getBytes(StandardCharsets.UTF_8));

        var ex = assertThrows(ResponseStatusException.class, () -> controller.putSchema("c", "t", ContentFormat.AVRO, null, file));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(schemas, never()).save(any());
    }

    @Test
    void stringAndJsonFormatsTakeNoSchema() {
        var file = new MockMultipartFile("file", "x", "text/plain", "x".getBytes(StandardCharsets.UTF_8));
        var ex = assertThrows(ResponseStatusException.class, () -> controller.putSchema("c", "t", ContentFormat.JSON, null, file));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void missingSchemaIs404() {
        when(schemas.findById("c::t")).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, controller.getSchema("c", "t").getStatusCode());
    }
}
