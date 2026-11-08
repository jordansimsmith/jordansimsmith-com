package com.jordansimsmith.tcginventory.reports.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.games.client.FindGamesRequest;
import com.jordansimsmith.tcginventory.games.client.Game;
import com.jordansimsmith.tcginventory.games.client.GamesService;
import com.jordansimsmith.tcginventory.inventory.client.FindSkusRequest;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsRequest;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.inventory.client.Sku;
import com.jordansimsmith.tcginventory.inventory.client.Unit;
import com.jordansimsmith.tcginventory.orders.client.FindOrdersRequest;
import com.jordansimsmith.tcginventory.orders.client.OrderService;
import com.jordansimsmith.tcginventory.orders.client.OrderState;
import com.jordansimsmith.tcginventory.orders.client.OrderSummary;
import com.jordansimsmith.tcginventory.reports.client.ReportPayload;
import com.jordansimsmith.time.Clock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReportMessageHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(ReportMessageHandler.class);

  private final ReportRepository reportRepository;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final GamesService gamesService;
  private final InventoryService inventoryService;
  private final OrderService orderService;

  public ReportMessageHandler(
      ReportRepository reportRepository,
      ObjectMapper objectMapper,
      Clock clock,
      GamesService gamesService,
      InventoryService inventoryService,
      OrderService orderService) {
    this.reportRepository = reportRepository;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.gamesService = gamesService;
    this.inventoryService = inventoryService;
    this.orderService = orderService;
  }

  public void handleMessage(RequestContext ctx, ReportMessage message) throws ServiceException {
    LOGGER.info("starting report generation {} for user {}", message.generationId(), ctx.user());
    var report = requireGeneration(ctx.user());
    if (!message.generationId().equals(report.getGenerationId())) {
      return;
    }

    switch (report.getGenerationStatus()) {
      case "queued" -> reportRepository.startGeneration(ctx.user(), message.generationId());
      case "running" -> {}
      case "succeeded" -> {
        return;
      }
      default -> throw new IllegalStateException("Report generation has an unknown status");
    }

    var generatedAt = clock.now();
    var payload = buildReport(ctx, generatedAt);
    var reportJson = serialize(payload);
    reportRepository.completeGeneration(
        ctx.user(), message.generationId(), reportJson, generatedAt, clock.now());
    LOGGER.info("completed report generation {} for user {}", message.generationId(), ctx.user());
  }

  private ReportItem requireGeneration(String user) {
    var report = reportRepository.get(user);
    if (report == null
        || report.getGenerationId() == null
        || report.getGenerationId().isBlank()
        || report.getGenerationStartedAt() == null
        || report.getGenerationStatus() == null) {
      throw new IllegalStateException("Report generation metadata is missing or incomplete");
    }
    if (!List.of("queued", "running", "succeeded").contains(report.getGenerationStatus())) {
      throw new IllegalStateException("Report generation has an unknown status");
    }
    if (report.getGenerationStatus().equals("succeeded")
        && report.getGenerationFinishedAt() == null) {
      throw new IllegalStateException("Completed report generation metadata is incomplete");
    }
    return report;
  }

  private ReportPayload buildReport(RequestContext ctx, Instant generatedAt)
      throws ServiceException {
    var games = gamesService.findGames(ctx, new FindGamesRequest()).games();
    var gamesById = games.stream().collect(Collectors.toMap(Game::id, game -> game));
    var accumulator = new ReportAccumulator(generatedAt);
    var gameAccumulators = new LinkedHashMap<String, ReportAccumulator>();
    for (var game : games) {
      gameAccumulators.put(game.id(), new ReportAccumulator(generatedAt));
    }

    var skuGames = new HashMap<String, String>();
    addSkus(ctx, gamesById, gameAccumulators, accumulator, skuGames);
    addOrders(accumulator, gameAccumulators, skuGames, collectOrders(ctx));
    return createPayload(games, accumulator, gameAccumulators);
  }

  private void addSkus(
      RequestContext ctx,
      Map<String, Game> gamesById,
      Map<String, ReportAccumulator> gameAccumulators,
      ReportAccumulator accumulator,
      Map<String, String> skuGames)
      throws ServiceException {
    String continuation = null;
    do {
      var response =
          inventoryService.findSkus(ctx, new FindSkusRequest(null, null, null, 100, continuation));
      for (var sku : response.skus()) {
        var game = gamesById.get(sku.game());
        if (game == null) {
          throw new IllegalStateException(
              "Inventory SKU references a game not returned by Games: " + sku.game());
        }
        skuGames.put(sku.skuId(), game.id());
        var units = collectUnits(ctx, sku);
        accumulator.addSku(sku, units);
        gameAccumulators.get(game.id()).addSku(sku, units);
      }
      continuation = response.nextContinuation();
    } while (continuation != null);
  }

  private List<Unit> collectUnits(RequestContext ctx, Sku sku) throws ServiceException {
    var units = new ArrayList<Unit>();
    String continuation = null;
    do {
      var response =
          inventoryService.findUnits(ctx, new FindUnitsRequest(sku.skuId(), 100, continuation));
      units.addAll(response.units());
      continuation = response.nextContinuation();
    } while (continuation != null);
    return units;
  }

  private List<OrderSummary> collectOrders(RequestContext ctx) throws ServiceException {
    var orders = new ArrayList<OrderSummary>();
    String continuation = null;
    do {
      var response = orderService.findOrders(ctx, new FindOrdersRequest(100, continuation));
      orders.addAll(response.orders());
      continuation = response.nextContinuation();
    } while (continuation != null);
    return orders;
  }

  private void addOrders(
      ReportAccumulator accumulator,
      Map<String, ReportAccumulator> gameAccumulators,
      Map<String, String> skuGames,
      List<OrderSummary> orders) {
    for (var order : orders) {
      accumulator.addOrder(order);
      var paid =
          order.state() == OrderState.TO_PICK
              || order.state() == OrderState.FULFILLING
              || order.state() == OrderState.FULFILLED;
      for (var row : order.rows()) {
        var game = skuGames.get(row.skuId());
        if (game == null) {
          throw new IllegalStateException("order line references missing SKU: " + row.skuId());
        }
        if (paid && row.price() != null) {
          gameAccumulators.get(game).addRevenue(new BigDecimal(row.price()));
        }
      }
    }
  }

  private ReportPayload createPayload(
      List<Game> games,
      ReportAccumulator accumulator,
      Map<String, ReportAccumulator> gameAccumulators) {
    return new ReportPayload(
        accumulator.toTotals(),
        accumulator.toRevenueByMonth(),
        accumulator.toIntakeVsSalesByWeek(),
        games.stream()
            .map(
                game -> {
                  var gameAccumulator = gameAccumulators.get(game.id());
                  return new ReportPayload.GameReport(
                      game.id(),
                      gameAccumulator.uniqueCardNamesCount(),
                      gameAccumulator.toTotals(),
                      gameAccumulator.toTopHits(),
                      gameAccumulator.toTopSets(),
                      gameAccumulator.toAgingBands(),
                      gameAccumulator.toPriceBuckets());
                })
            .toList());
  }

  private String serialize(ReportPayload payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Could not serialize report payload", e);
    }
  }
}
