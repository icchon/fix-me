package com.github.icchon.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.icchon.model.Market;
import com.github.icchon.service.MarketService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/markets")
public class MarketController {
    private final MarketService marketService;

    public MarketController(MarketService marketService) {
        this.marketService = marketService;
    }

    @GetMapping
    public List<Market> getAll() {
        return marketService.getAllMarkets();
    }

    @GetMapping("/{idOrName}")
    public Market get(@PathVariable("idOrName") String idOrName) {
        Market market = marketService.getMarketByName(idOrName);
        if (market == null) {
            market = marketService.getMarketById(idOrName);
        }
        return market;
    }

    @PostMapping
    public Market create(@RequestBody MarketRequest request) {
        System.out.println("[ID-ISSUER] Received POST /markets: " + request);
        return marketService.createMarket(
                request.marketName(), 
                request.domain(), 
                request.port(),
                request.routerId(),
                request.sessionId()
        );
    }

    @PutMapping("/{marketId}")
    public Market update(@PathVariable("marketId") String marketId, @RequestBody MarketRequest request) {
        return marketService.updateMarket(
                marketId, 
                request.marketName(), 
                request.domain(), 
                request.port(),
                request.routerId(),
                request.sessionId()
        );
    }

    @DeleteMapping("/{marketId}")
    public void delete(@PathVariable("marketId") String marketId) {
        marketService.deleteMarket(marketId);
    }

    @GetMapping("/{idOrName}/endpoints")
    public List<String> getEndpoints(@PathVariable("idOrName") String idOrName) {
        return marketService.getMarketEndpoints(idOrName);
    }

    @DeleteMapping("/name/{name}")
    public void deleteByName(
            @PathVariable("name") String name,
            @RequestParam(value = "router_id", required = false) String routerId,
            @RequestParam(value = "session_id", required = false) String sessionId) {
        if (routerId != null && sessionId != null) {
            marketService.unregisterEndpoint(name, routerId, sessionId);
        } else {
            marketService.deleteMarketByName(name);
        }
    }

    public record MarketRequest(
            @JsonProperty("market_name") String marketName,
            @JsonProperty("domain") String domain,
            @JsonProperty("port") int port,
            @JsonProperty("router_id") String routerId,
            @JsonProperty("session_id") String sessionId
    ) {}
}
