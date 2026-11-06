package com.ticknova.workspace;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class AnalyticsEventsController {
    private final AnalyticsEvents events;
    public AnalyticsEventsController(AnalyticsEvents events) { this.events = events; }

    @GetMapping(value="/api/v1/workspace/analytics/events", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(@RequestHeader("Authorization") String authorization) {
        // The existing BearerSessionFilter validates the format before this method.
        return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Accel-Buffering","no")
            .contentType(MediaType.TEXT_EVENT_STREAM).body(events.subscribe(authorization.substring(7)));
    }
}
