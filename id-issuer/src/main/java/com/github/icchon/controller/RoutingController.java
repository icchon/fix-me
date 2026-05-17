package com.github.icchon.controller;

import com.github.icchon.service.MarketService;
import com.github.icchon.service.RouterDiscoveryService;
import com.github.icchon.service.RoutingService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/routing")
public class RoutingController {
    private final RoutingService routingService;
    private final MarketService marketService;
    private final RouterDiscoveryService discoveryService;

    public RoutingController(RoutingService routingService, MarketService marketService, RouterDiscoveryService discoveryService) {
        this.routingService = routingService;
        this.marketService = marketService;
        this.discoveryService = discoveryService;
    }

    @PostMapping("/{alias}")
    public void register(@PathVariable("alias") String alias, @RequestBody String routerId) {
        routingService.register(alias, routerId);
    }

    @GetMapping("/{alias}/endpoints")
    public List<String> getEndpoints(@PathVariable("alias") String alias) {
        List<String> activeRouterIds = discoveryService.getAllRouters().stream()
                .map(com.github.icchon.model.RouterInfo::id)
                .toList();

        // 1. まずマーケットとして探す
        List<String> endpoints = marketService.getMarketEndpoints(alias);
        if (endpoints != null && !endpoints.isEmpty()) {
            // 生きているルーターのエンドポイントのみに絞り込む
            return endpoints.stream()
                    .filter(ep -> {
                        String rId = ep.split(":")[0];
                        return activeRouterIds.contains(rId);
                    })
                    .toList();
        }

        // 2. 次にブローカーなどの一般ルーティングとして探す
        String routerId = routingService.getRouterId(alias);
        if (routerId != null && activeRouterIds.contains(routerId)) {
            // Broker の場合は sessionId も alias と同じ (FIX ID) なので
            // "routerId:alias" の形式で返す
            return List.of(routerId + ":" + alias);
        }

        return List.of();
    }
}
