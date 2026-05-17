package com.github.icchon.controller;

import com.github.icchon.model.TradingSession;
import com.github.icchon.service.SessionService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/sessions")
public class SessionController {
    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    public String create(@RequestBody(required = false) SessionRequest request) {
        String marketId = (request != null) ? request.market_id() : null;
        return sessionService.createSession(marketId).brokerSessionId();
    }

    @GetMapping("/{sessionId}")
    public TradingSession get(@PathVariable("sessionId") String sessionId) {
        return sessionService.getSession(sessionId);
    }

    @GetMapping("/{sessionId}/validate")
    public boolean validate(@PathVariable("sessionId") String sessionId) {
        return sessionService.validateSession(sessionId);
    }

    public record SessionRequest(String market_id) {}
}
