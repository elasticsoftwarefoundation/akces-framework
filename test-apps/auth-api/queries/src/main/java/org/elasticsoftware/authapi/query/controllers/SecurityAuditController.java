package org.elasticsoftware.authapi.query.controllers;

import org.elasticsoftware.akces.query.models.QueryModels;
import org.elasticsoftware.authapi.query.models.SecurityAuditQueryModel.SecurityAuditState;
import org.elasticsoftware.authapi.query.models.SecurityAuditQueryModel.SecurityEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST controller for security audit operations.
 */
@RestController
@RequestMapping("/api/audit")
public class SecurityAuditController {
    
    private final QueryModels queryModels;
    
    @Autowired
    public SecurityAuditController(QueryModels queryModels) {
        this.queryModels = queryModels;
    }
    
    /**
     * Get security audit events for a user.
     */
    @GetMapping("/events/{userId}")
    @PreAuthorize("authentication.principal.uuid == #userId.toString() or hasRole('ADMIN')")
    public ResponseEntity<Page<SecurityEventResponse>> getUserAuditEvents(
            @PathVariable UUID userId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) Instant fromDate,
            @RequestParam(required = false) Instant toDate,
            Pageable pageable
    ) {
        // Get security audit from query model
        SecurityAuditState state = queryModels.getHydratedState(SecurityAuditState.class, userId);
        
        if (state == null) {
            return ResponseEntity.notFound().build();
        }
        
        // Filter events based on criteria
        List<SecurityEvent> filteredEvents = state.events().stream()
                .filter(event -> eventType == null || event.eventType().equals(eventType))
                .filter(event -> fromDate == null || event.timestamp().isAfter(fromDate) || event.timestamp().equals(fromDate))
                .filter(event -> toDate == null || event.timestamp().isBefore(toDate) || event.timestamp().equals(toDate))
                .sorted(Comparator.comparing(SecurityEvent::timestamp).reversed())
                .collect(Collectors.toList());
        
        // Apply pagination
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), filteredEvents.size());
        List<SecurityEvent> paginatedEvents = start < end ? filteredEvents.subList(start, end) : new ArrayList<>();
        
        // Convert to response DTOs
        List<SecurityEventResponse> responseEvents = paginatedEvents.stream()
                .map(event -> new SecurityEventResponse(
                        event.eventId(),
                        event.eventType(),
                        event.deviceInfo() != null ? event.deviceInfo().deviceType() : null,
                        event.deviceInfo() != null ? event.deviceInfo().deviceName() : null,
                        event.timestamp(),
                        event.success(),
                        event.details()
                ))
                .collect(Collectors.toList());
        
        Page<SecurityEventResponse> page = new PageImpl<>(responseEvents, pageable, filteredEvents.size());
        return ResponseEntity.ok(page);
    }
    
    /**
     * DTO for security event response.
     */
    public record SecurityEventResponse(
            UUID eventId,
            String eventType,
            String deviceType,
            String deviceName,
            Instant timestamp,
            boolean success,
            String details
    ) {
    }
}