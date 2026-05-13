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
    public TradingSession create(@RequestBody SessionRequest request) {
        return sessionService.createSession(request.market_id());
    }

    @GetMapping("/{sessionId}")
    public TradingSession get(@PathVariable("sessionId") String sessionId) {
        return sessionService.getSession(sessionId);
    }

    public record SessionRequest(String market_id) {}
}
