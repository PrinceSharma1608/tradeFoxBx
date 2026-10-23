package com.tradeFox.api;

import com.tradeFox.service.AuthService;
import com.tradeFox.service.IndstocksMarketDataService;
import com.tradeFox.service.PortfolioTradingService;
import com.tradeFox.service.StockDetailService;
import com.tradeFox.service.UserService;
import com.tradeFox.service.UserService.UserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
public class ApiController {
    private final AuthService authService;
    private final UserService userService;
    private final IndstocksMarketDataService marketDataService;
    private final PortfolioTradingService portfolioTradingService;
    private final StockDetailService stockDetailService;

    public ApiController(AuthService authService,
                         UserService userService,
                         IndstocksMarketDataService marketDataService,
                         PortfolioTradingService portfolioTradingService,
                         StockDetailService stockDetailService) {
        this.authService = authService;
        this.userService = userService;
        this.marketDataService = marketDataService;
        this.portfolioTradingService = portfolioTradingService;
        this.stockDetailService = stockDetailService;
    }

    @PostMapping("/api/auth/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> signup(@RequestBody SignupRequest request) {
        return authService.signup(request.name(), request.email(), request.password());
    }

    @PostMapping("/api/auth/login")
    public Map<String, Object> login(@RequestBody LoginRequest request) {
        return authService.login(request.email(), request.password());
    }

    @GetMapping("/api/market/quotes")
    public Map<String, Object> getMarketQuotes() {
        return stockDetailService.getLiveMarketQuotes();
    }

    @GetMapping("/api/market/indices")
    public List<IndstocksMarketDataService.MarketIndexQuote> getMarketIndices() {
        return marketDataService.getMarketIndices();
    }

    @GetMapping("/api/market/indices/history")
    public IndstocksMarketDataService.IndexHistory getIndexHistory(
            @RequestParam("name") String name,
            @RequestParam long startTime,
            @RequestParam long endTime) {
        return marketDataService.getIndexHistory(name, startTime, endTime);
    }

    @GetMapping("/api/market/stocks")
    public IndstocksMarketDataService.StockQuote getStockQuote(@RequestParam("name") String name) {
        return marketDataService.getStockQuote(name);
    }

    @GetMapping("/api/market/stocks/quotes")
    public List<IndstocksMarketDataService.StockQuote> getStockQuotes(
            @RequestParam("symbols") List<String> symbols) {
        return marketDataService.getStockQuotes(symbols);
    }

    @GetMapping("/api/market/stocks/search")
    public List<IndstocksMarketDataService.StockSearchResult> searchStocks(
            @RequestParam("q") String query,
            @RequestParam(defaultValue = "20") int limit) {
        return marketDataService.searchStocks(query, limit);
    }

    @GetMapping("/api/market/history")
    public IndstocksMarketDataService.HistoricalSeries getMarketHistory(
            @RequestParam("name") String name,
            @RequestParam(defaultValue = "1day") String interval,
            @RequestParam long startTime,
            @RequestParam long endTime) {
        return marketDataService.getHistoricalData(name, interval, startTime, endTime);
    }

    @GetMapping("/api/market/intervals")
    public List<IndstocksMarketDataService.HistoricalInterval> getMarketIntervals() {
        return marketDataService.getHistoricalIntervals();
    }

    @GetMapping("/api/market/stocks/details")
    public StockDetailService.StockDetails getStockDetails(@RequestParam("symbol") String symbol) {
        return stockDetailService.getStockDetails(symbol);
    }

    @GetMapping("/api/market/stocks/chart")
    public List<StockDetailService.ChartPoint> getStockChart(
            @RequestParam("symbol") String symbol,
            @RequestParam(defaultValue = "1D") String range) {
        return stockDetailService.getChartData(symbol, range);
    }

    @GetMapping("/api/users/{userId}")
    public UserDetails getUserDetails(@PathVariable long userId) {
        return userService.getUserDetails(userId);
    }

    // --- Portfolio & Order Handshake Endpoints (Persisted in PostgreSQL) ---

    @PostMapping("/api/orders")
    public PortfolioTradingService.OrderResult executeOrder(@RequestBody OrderRequest request) {
        return portfolioTradingService.executeOrder(
                request.userId(),
                request.stockSymbol(),
                request.stockName(),
                request.orderType(),
                request.price(),
                request.quantity(),
                request.executionType(),
                request.marketPrice()
        );
    }

    @PostMapping("/api/orders/{orderId}/cancel")
    public Map<String, Object> cancelOrder(@PathVariable("orderId") long orderId, @RequestParam("userId") long userId) {
        return portfolioTradingService.cancelOrder(userId, orderId);
    }

    @GetMapping("/api/orders")
    public List<PortfolioTradingService.OrderItem> getOrders(@RequestParam("userId") long userId) {
        return portfolioTradingService.getOrders(userId);
    }

    @GetMapping("/api/holdings")
    public List<PortfolioTradingService.HoldingItem> getHoldings(@RequestParam("userId") long userId) {
        return portfolioTradingService.getHoldings(userId);
    }

    @GetMapping("/api/watchlist")
    public List<PortfolioTradingService.WatchlistDataItem> getWatchlist(@RequestParam("userId") long userId) {
        return portfolioTradingService.getWatchlistData(userId);
    }

    @PostMapping("/api/watchlist")
    public Map<String, Object> addToWatchlist(@RequestBody WatchlistRequest request) {
        return portfolioTradingService.addToWatchlist(request.userId(), request.stockSymbol(), request.stockName());
    }

    @DeleteMapping("/api/watchlist")
    public Map<String, Object> removeFromWatchlist(@RequestParam("userId") long userId,
                                                   @RequestParam("symbol") String symbol) {
        return portfolioTradingService.removeFromWatchlist(userId, symbol);
    }

    public record SignupRequest(String name, String email, String password) { }
    public record LoginRequest(String email, String password) { }
    public record OrderRequest(
            long userId,
            String stockSymbol,
            String stockName,
            String orderType,
            BigDecimal price,
            int quantity,
            String executionType,
            BigDecimal marketPrice
    ) { }
    public record WatchlistRequest(long userId, String stockSymbol, String stockName) { }
}
