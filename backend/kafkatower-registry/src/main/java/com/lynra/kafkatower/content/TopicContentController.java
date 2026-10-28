package com.lynra.kafkatower.content;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Topic content browsing. Reads message payloads, so access is limited to the roles in
 * {@code app.security.topic-content-roles} (see SecurityConfig) — ADMIN by default.
 */
@RestController
@RequestMapping("/api/topics/content")
public class TopicContentController {

    private static final Logger log = LoggerFactory.getLogger(TopicContentController.class);
    private static final long MAX_SCHEMA_BYTES = 1024 * 1024;

    public record SchemaInfo(ContentFormat format, String messageName, Instant updatedAt) {
        static SchemaInfo of(TopicSchema s) {
            return new SchemaInfo(s.getFormat(), s.getMessageName(), s.getUpdatedAt());
        }
    }

    /** Spring hides exception messages by default; the UI needs them (e.g. why a schema was rejected). */
    public record ApiError(String message) {
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> onStatus(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(new ApiError(e.getReason()));
    }

    private final ContentSearchService search;
    private final TopicSchemaRepository schemas;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public TopicContentController(ContentSearchService search, TopicSchemaRepository schemas) {
        this.search = search;
        this.schemas = schemas;
    }

    /** Streams matches as SSE `data:` lines of {@link SearchEvent} JSON; closing the connection cancels the search. */
    @PostMapping(value = "/search", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter search(@RequestBody ContentSearchRequest req) {
        if (req.clusterName() == null || req.topic() == null || req.topic().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "clusterName and topic are required");
        }
        ContentFormat format = req.format() == null ? ContentFormat.STRING : req.format();
        PayloadDecoder decoder = decoderFor(req, format);

        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean cancelled = new AtomicBoolean();
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(e -> cancelled.set(true));

        executor.submit(() -> {
            try {
                search.search(req, decoder, event -> send(emitter, event, cancelled), cancelled::get);
            } catch (Exception e) {
                log.warn("Content search on {}/{} failed: {}", req.clusterName(), req.topic(), e.getMessage());
                send(emitter, SearchEvent.error(e.getMessage()), cancelled);
            } finally {
                emitter.complete();
            }
        });
        return emitter;
    }

    private PayloadDecoder decoderFor(ContentSearchRequest req, ContentFormat format) {
        TopicSchema schema = format.needsSchema()
                ? schemas.findById(TopicSchema.idOf(req.clusterName(), req.topic())).orElse(null)
                : null;
        try {
            return PayloadDecoder.create(format, schema);
        } catch (IllegalArgumentException e) {
            log.warn("Stored schema for {}/{} is unusable, showing raw strings: {}", req.clusterName(), req.topic(), e.getMessage());
            return PayloadDecoder.plain();
        }
    }

    private void send(SseEmitter emitter, SearchEvent event, AtomicBoolean cancelled) {
        try {
            emitter.send(SseEmitter.event().data(event, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            cancelled.set(true); // client went away
        }
    }

    @PutMapping(value = "/schema", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SchemaInfo putSchema(@RequestParam String clusterName, @RequestParam String topic,
                                @RequestParam ContentFormat format,
                                @RequestParam(required = false) String messageName,
                                @RequestPart MultipartFile file) throws IOException {
        if (!format.needsSchema()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only AVRO and PROTOBUF take a schema");
        }
        if (file.isEmpty() || file.getSize() > MAX_SCHEMA_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Schema file must be 1 byte to 1 MB");
        }
        byte[] bytes = file.getBytes();
        String content = format == ContentFormat.AVRO
                ? new String(bytes, StandardCharsets.UTF_8)
                : Base64.getEncoder().encodeToString(bytes);
        TopicSchema schema = new TopicSchema(clusterName, topic, format, messageName, content);
        try {
            PayloadDecoder.create(format, schema); // reject unusable schemas up front
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return SchemaInfo.of(schemas.save(schema));
    }

    @GetMapping("/schema")
    public ResponseEntity<SchemaInfo> getSchema(@RequestParam String clusterName, @RequestParam String topic) {
        return schemas.findById(TopicSchema.idOf(clusterName, topic))
                .map(s -> ResponseEntity.ok(SchemaInfo.of(s)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/schema")
    public ResponseEntity<Void> deleteSchema(@RequestParam String clusterName, @RequestParam String topic) {
        schemas.deleteById(TopicSchema.idOf(clusterName, topic));
        return ResponseEntity.noContent().build();
    }
}
