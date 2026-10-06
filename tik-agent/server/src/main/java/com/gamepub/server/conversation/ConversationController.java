package com.gamepub.server.conversation;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.gamepub.server.conversation.ConversationDtos.*;

@Validated
@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {
    private final ConversationService service;

    public ConversationController(ConversationService service) { this.service = service; }

    @GetMapping
    public List<ConversationResponse> list(@RequestParam(required = false) Long beforeId,
                                          @RequestParam(defaultValue = "30") @Min(1) @Max(100) int limit) {
        return service.list(beforeId, limit).stream().map(ConversationResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<ConversationResponse> create() {
        return ResponseEntity.status(201).body(ConversationResponse.from(service.create()));
    }

    @PatchMapping("/{id}")
    public ConversationResponse rename(@PathVariable long id, @Valid @RequestBody RenameRequest request) {
        return ConversationResponse.from(service.rename(id, request.title()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/messages")
    public List<MessageResponse> messages(@PathVariable long id) {
        return service.messages(id).stream().map(MessageResponse::from).toList();
    }
}
