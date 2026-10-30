package com.jordansimsmith.tcginventory.reports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.AuditItem;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.JobProcessor;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.games.client.FindGamesRequest;
import com.jordansimsmith.tcginventory.games.client.Game;
import com.jordansimsmith.tcginventory.games.client.GamesService;
import com.jordansimsmith.tcginventory.inventory.SkuItem;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsRequest;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.inventory.client.Unit;
import com.jordansimsmith.tcginventory.orders.OrderItem;
import com.jordansimsmith.time.Clock;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class ReportJobProcessor implements JobProcessor {
  private static final Logger LOGGER = LoggerFactory.getLogger(ReportJobProcessor.class);

  private final DynamoDbTable<ReportItem> reportTable;
  private final InventoryService inventoryService;
  private final DynamoDbTable<AuditItem> auditTable;
  private final DynamoDbTable<OrderItem> orderTable;
  private final DynamoDbIndex<SkuItem> gsi2Index;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final GamesService gamesService;

  public ReportJobProcessor(
      DynamoDbTable<ReportItem> reportTable,
      DynamoDbTable<AuditItem> auditTable,
      DynamoDbTable<OrderItem> orderTable,
      DynamoDbTable<SkuItem> skuTable,
      ObjectMapper objectMapper,
      Clock clock,
      GamesService gamesService,
      InventoryService inventoryService) {
    this.reportTable = reportTable;
    this.auditTable = auditTable;
    this.orderTable = orderTable;
    this.gsi2Index = skuTable.index(TcgInventoryTable.GSI2_NAME);
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.gamesService = gamesService;
    this.inventoryService = inventoryService;
  }

  @Override
  public JobProcessor.SuccessJobResult processBatch(String user, JobItem jobItem)
      throws ServiceException {
    LOGGER.info("starting report job for user {}", user);

    var asOfAuditUlid = findLatestAuditUlid(user);
    LOGGER.info("captured as-of audit ULID: {}", asOfAuditUlid);

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

    for (var sku : pageGsi2Skus(user)) {
      var game = gamesById.get(sku.getGame());
      if (game == null) {
        throw new IllegalStateException(
            "Inventory SKU references a game not returned by Games: " + sku.getGame());
      }
      skuGames.put(sku.getSkuId(), game.id());
      var units = new ArrayList<Unit>();
      String continuation = null;
      do {
        var response =
            inventoryService.findUnits(
                ctx, new FindUnitsRequest(sku.getSkuId(), 100, continuation));
        units.addAll(response.units());
        continuation = response.nextContinuation();
      } while (continuation != null);
      accumulator.addSku(sku, units);
      gameAccumulators.get(game.id()).addSku(sku, units);
    }

    for (var order : pageOrders(user)) {
      accumulator.addOrder(order);
      var paid =
          "to_pick".equals(order.getStatus())
              || "fulfilling".equals(order.getStatus())
              || "fulfilled".equals(order.getStatus());
      for (var line : order.getLines()) {
        var game = skuGames.get(line.getSkuId());
        if (game == null) {
          throw new IllegalStateException("order line references missing SKU: " + line.getSkuId());
        }
        if (paid && line.getPrice() != null) {
          gameAccumulators.get(game).addRevenue(new BigDecimal(line.getPrice()));
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
      var reportItem = ReportItem.create(user, reportJson, asOfAuditUlid, now);
      reportTable.putItem(reportItem);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    LOGGER.info("wrote report snapshot");
    return new JobProcessor.SuccessJobResult(0, null);
  }

  private String findLatestAuditUlid(String user) {
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.keyEqualTo(
                    Key.builder().partitionValue(AuditItem.formatPk(user)).build()))
            .scanIndexForward(false)
            .limit(1)
            .build();

    return auditTable.query(request).stream()
        .flatMap(page -> page.items().stream())
        .findFirst()
        .map(AuditItem::getSk)
        .orElse(null);
  }

  private List<SkuItem> pageGsi2Skus(String user) {
    var gsi2pk = SkuItem.formatGsi2pk(user);
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.keyEqualTo(Key.builder().partitionValue(gsi2pk).build()))
            .scanIndexForward(true)
            .build();

    return gsi2Index.query(request).stream().flatMap(page -> page.items().stream()).toList();
  }

  private List<OrderItem> pageOrders(String user) {
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(SkuItem.formatUserPk(user))
                        .sortValue(OrderItem.ORDER_PREFIX)
                        .build()))
            .scanIndexForward(true)
            .build();

    return orderTable.query(request).stream().flatMap(page -> page.items().stream()).toList();
  }
}
