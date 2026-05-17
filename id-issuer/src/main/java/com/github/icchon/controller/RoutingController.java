package com.github.icchon.controller;

import com.github.icchon.service.RoutingService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/routing")
public class RoutingController {
    private final RoutingService routingService;

    public RoutingController(RoutingService routingService) {
        this.routingService = routingService;
    }

    @PostMapping("/{alias}")
    public void register(@PathVariable("alias") String alias, @RequestBody String routerId) {
        routingService.register(alias, routerId);
    }

    @GetMapping("/{alias}")
    public String getRouterId(@PathVariable("alias") String alias) {
        return routingService.getRouterId(alias);
    }

    @DeleteMapping("/{alias}")
    public void unregister(@PathVariable("alias") String alias) {
        routingService.unregister(alias);
    }
}
