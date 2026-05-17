package com.github.icchon.controller;

import com.github.icchon.model.RouterInfo;
import com.github.icchon.service.RouterDiscoveryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/routers")
public class RouterDiscoveryController {
    private final RouterDiscoveryService discoveryService;

    public RouterDiscoveryController(RouterDiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    @PostMapping
    public void register(@RequestBody RouterInfo router) {
        discoveryService.register(router);
    }

    @GetMapping
    public List<RouterInfo> getAll() {
        return discoveryService.getAllRouters();
    }

    @DeleteMapping("/{id}")
    public void unregister(@PathVariable String id) {
        discoveryService.unregister(id);
    }
}
