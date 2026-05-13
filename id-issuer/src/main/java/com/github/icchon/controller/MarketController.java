package com.github.icchon.controller;

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

    @GetMapping("/{marketId}")
    public Market getById(@PathVariable("marketId") String marketId) {
        return marketService.getMarketById(marketId);
    }

    @PostMapping
    public Market create(@RequestBody MarketRequest request) {
        return marketService.createMarket(request.marketName(), request.domain(), request.port());
    }

    @PutMapping("/{marketId}")
    public Market update(@PathVariable("marketId") String marketId, @RequestBody MarketRequest request) {
        return marketService.updateMarket(marketId, request.marketName(), request.domain(), request.port());
    }

    @DeleteMapping("/{marketId}")
    public void delete(@PathVariable("marketId") String marketId) {
        marketService.deleteMarket(marketId);
    }

    public record MarketRequest(String marketName, String domain, int port) {}
}
