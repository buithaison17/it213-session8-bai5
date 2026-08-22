package com.example.bai5;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/chat")
public class MultiTenantChatController {
    private final MultiTenantRAGService ragService;

    public MultiTenantChatController(MultiTenantRAGService ragService) {
        this.ragService = ragService;
    }

    @PostMapping
    public ResponseEntity<?> chat(
            @RequestHeader(value = "X-Tenant-ID") String tenantId,
            @RequestBody Map<String, String> body) {

        String query = body.get("message");
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().body("Nội dung tin nhắn không được để trống.");
        }

        String answer = ragService.chatWithTenantDocs(query, tenantId);
        return ResponseEntity.ok(Map.of(
                "tenant_id", tenantId,
                "question", query,
                "answer", answer
        ));
    }
}
