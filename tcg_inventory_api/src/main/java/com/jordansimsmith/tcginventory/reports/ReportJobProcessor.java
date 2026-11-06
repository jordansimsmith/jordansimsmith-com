package com.jordansimsmith.tcginventory.reports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.JobProcessor;
import com.jordansimsmith.tcginventory.games.client.FindGamesRequest;
import com.jordansimsmith.tcginventory.games.client.Game;
import com.jordansimsmith.tcginventory.games.client.GamesService;
import com.jordansimsmith.tcginventory.inventory.client.FindSkusRequest;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsRequest;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.inventory.client.Unit;
import com.jordansimsmith.tcginventory.orders.client.FindOrdersRequest;
import com.jordansimsmith.tcginventory.orders.client.OrderService;
import com.jordansimsmith.tcginventory.orders.client.OrderState;
import com.jordansimsmith.tcginventory.orders.client.OrderSummary;
import com.jordansimsmith.time.Clock;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

public class ReportJobProcessor implements JobProcessor {
  private static final Logger LOGGER = LoggerFactory.getLogger(ReportJobProcessor.class);

  private final DynamoDbTable<ReportItem> reportTable;
  private final InventoryService inventoryService;
  private final OrderService orderService;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final GamesService gamesService;

  public ReportJobProcessor(
      DynamoDbTable<ReportItem> reportTable,
      ObjectMapper objectMapper,
      Clock clock,
      GamesService gamesService,
      InventoryService inventoryService,
      OrderService orderService) {
    this.reportTable = reportTable;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.gamesService = gamesService;
    this.inventoryService = inventoryService;
    this.orderService = orderService;
  }

  @Override
  public JobProcessor.SuccessJobResult processBatch(String user, JobItem jobItem)
      throws ServiceException {
    LOGGER.info("starting report job for user {}", user);

    var now = clock.now();
    var ctx = new RequestContext(user);
    var games = gamesService.findGames(ctx, new FindGamesRequest()).games();
    var gamesById = games.stream().collect(Collectors.toMap(Game::id, game -> game));
    var accumulator = new ReportAccumulator(now);
    var gameAccumulators = new LinkedHashMap<String, ReportAccumulator>();
    for (var game : games) {
      gameAccumulators.put(game.id(), new ReportAccumulator(now));
    }
    var skuGames = new HashMap<String, String>();

    String skuContinuation = null;
    do {
      var skuResponse =
          inventoryService.findSkus(
              ctx, new FindSkusRequest(null, null, null, 100, skuContinuation));
      for (var sku : skuResponse.skus()) {
        var game = gamesById.get(sku.game());
        if (game == null) {
          throw new IllegalStateException(
              "Inventory SKU references a game not returned by Games: " + sku.game());
        }
        skuGames.put(sku.skuId(), game.id());
        var units = new ArrayList<Unit>();
        String continuation = null;
        do {
          var response =
              inventoryService.findUnits(ctx, new FindUnitsRequest(sku.skuId(), 100, continuation));
          units.addAll(response.units());
          continuation = response.nextContinuation();
        } while (continuation != null);
        accumulator.addSku(sku, units);
        gameAccumulators.get(game.id()).addSku(sku, units);
      }
      skuContinuation = skuResponse.nextContinuation();
    } while (skuContinuation != null);

    var orders = new ArrayList<OrderSummary>();
    String orderContinuation = null;
    do {
      var orderResponse =
          orderService.findOrders(ctx, new FindOrdersRequest(100, orderContinuation));
      orders.addAll(orderResponse.orders());
      orderContinuation = orderResponse.nextContinuation();
    } while (orderContinuation != null);

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

    try {
      var payload =
          new ReportPayload(
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
      var reportJson = objectMapper.writeValueAsString(payload);
      var reportItem = ReportItem.create(user, reportJson, now);
      reportTable.putItem(reportItem);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    LOGGER.info("wrote report snapshot");
    return new JobProcessor.SuccessJobResult(0, null);
  }
}
