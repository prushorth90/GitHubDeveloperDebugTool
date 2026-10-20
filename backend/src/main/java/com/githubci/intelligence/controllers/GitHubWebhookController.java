package com.githubci.intelligence.controllers;

import com.githubci.intelligence.services.GitHubWebhookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/webhooks/github")
public class GitHubWebhookController {

    private final GitHubWebhookService service;

    public GitHubWebhookController(GitHubWebhookService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestBody(required = false) byte[] body,
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(name = "X-GitHub-Event", required = false) String event,
            @RequestHeader(name = "X-GitHub-Delivery", required = false) String deliveryId) {
        service.receive(body == null ? new byte[0] : body, signature, event, deliveryId);
        return ResponseEntity.noContent().build();
    }
}
