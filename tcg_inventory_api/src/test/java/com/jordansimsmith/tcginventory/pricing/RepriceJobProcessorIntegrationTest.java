package com.jordansimsmith.tcginventory.pricing;

import static com.jordansimsmith.tcginventory.pricing.client.Condition.NM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.queue.FakeQueueClient;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.JobMessage;
import com.jordansimsmith.tcginventory.JobProcessor;
import com.jordansimsmith.tcginventory.JobsHandler;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import com.jordansimsmith.tcginventory.fetchtcg.client.CardListing;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetCardResponse;
import com.jordansimsmith.tcginventory.inventory.SkuItem;
import com.jordansimsmith.tcginventory.inventory.UnitItem;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import com.jordansimsmith.tcginventory.inventory.client.FindSkusRequest;
import com.jordansimsmith.tcginventory.inventory.client.FindSkusResponse;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsRequest;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsResponse;
import com.jordansimsmith.tcginventory.inventory.client.GetSkuRequest;
import com.jordansimsmith.tcginventory.inventory.client.GetSkuResponse;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.inventory.client.Sku;
import com.jordansimsmith.tcginventory.inventory.client.Unit;
import com.jordansimsmith.tcginventory.inventory.client.UnitStatus;
import com.jordansimsmith.tcginventory.inventory.client.UpdateSkuRequest;
import com.jordansimsmith.tcginventory.pricing.client.GetPriceRequest;
import com.jordansimsmith.tcginventory.pricing.client.GetPriceResponse;
import com.jordansimsmith.tcginventory.pricing.client.PricingService;
import com.jordansimsmith.time.FakeClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

@Testcontainers
public class RepriceJobProcessorIntegrationTest {
  private static final Instant CREATED_AT = Instant.ofEpochSecond(1700000000);

  private FakeClock clock;
  private FakeQueueClient<JobMessage> jobsQueue;
  private ObjectMapper objectMapper;
  private TcgInventoryTestFactory factory;
  private JobsHandler jobsHandler;
  private DynamoDbTable<JobItem> jobTable;
  private DynamoDbTable<SkuItem> skuTable;
  private DynamoDbTable<UnitItem> unitTable;

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeAll
  static void setUpBeforeClass() {
    var factory =
        TcgInventoryTestFactory.create(
            dynamoDbContainer.getEndpoint(), TcgInventoryTestFactory.UNUSED_S3_ENDPOINT);
    DynamoDbUtils.createTable(factory.dynamoDbClient(), factory.tableDefinition());
  }

  @BeforeEach
  void setUp() {
    factory =
        TcgInventoryTestFactory.create(
            dynamoDbContainer.getEndpoint(), TcgInventoryTestFactory.UNUSED_S3_ENDPOINT);
    clock = factory.fakeClock();
    jobsQueue = factory.fakeJobsQueue();
    objectMapper = factory.objectMapper();
    jobTable = factory.jobTable();
    skuTable = factory.skuTable();
    unitTable = factory.unitTable();
    DynamoDbUtils.reset(factory.dynamoDbClient());
    jobsQueue.reset();
    factory.fakeFetchTcgService().reset();
    clock.setTime(CREATED_AT);
    jobsHandler = new JobsHandler(factory);
  }

  @Test
  void repriceShouldUpdateInStockGamesAndAuditUnchangedPrices() {
    // arrange
    var mtgSkuId = createSku("jordan", "mtg", "scryfall-001", "fetch-mtg", "2.90", "in_stock");
    var pokemonSkuId = createSku("jordan", "pokemon", "12345", "fetch-pokemon", "1.50", "in_stock");
    var lowMarketSkuId =
        createSku("jordan", "mtg", "scryfall-low", "fetch-low", "0.10", "in_stock");
    var reservedSkuId = createSku("jordan", "pokemon", "12346", null, "3.00", "reserved");
    var otherUserSkuId =
        createSku("other-user", "mtg", "scryfall-001", "fetch-mtg", "2.90", "in_stock");

    seedCard("fetch-mtg", "2.50");
    seedCard("fetch-pokemon", "1.00");
    seedCard("fetch-low", "0.10");
    factory
        .fakeFetchTcgService()
        .seedListings(
            "fetch-mtg", List.of(new CardListing(1, "raw-nm", new BigDecimal("1.00"), "jordan")));
    createRepriceJob("jordan", "job1", "0.75");

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "reprice"), null);

    // assert
    var job = getJob("jordan", "job1");
    assertThat(job.getStatus()).isEqualTo("succeeded");
    assertThat(job.getProcessedCount()).isEqualTo(4);

    var mtgSkuAfter = getSku("jordan", mtgSkuId);
    assertThat(mtgSkuAfter.getSuggestedPrice()).isEqualTo("2.90");
    assertThat(mtgSkuAfter.getDirty()).isTrue();
    assertThat(mtgSkuAfter.getVersion()).isEqualTo(2);

    var pokemonSkuAfter = getSku("jordan", pokemonSkuId);
    assertThat(pokemonSkuAfter.getSuggestedPrice()).isEqualTo("1.00");
    assertThat(pokemonSkuAfter.getDirty()).isTrue();
    assertThat(pokemonSkuAfter.getVersion()).isEqualTo(2);
    assertThat(getSku("jordan", lowMarketSkuId).getSuggestedPrice()).isEqualTo("0.75");
    assertThat(getSku("jordan", lowMarketSkuId).getVersion()).isEqualTo(2);
    assertThat(getSku("jordan", reservedSkuId).getVersion()).isEqualTo(1);
    assertThat(getSku("other-user", otherUserSkuId).getVersion()).isEqualTo(1);
    assertThat(getUnit("jordan", pokemonSkuId).getStatus()).isEqualTo("in_stock");

    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "reprice"), null);
    assertThat(getSku("jordan", mtgSkuId).getVersion()).isEqualTo(2);
  }

  @Test
  void repriceShouldUseDefaultFloorWhenPricingItemIsAbsent() {
    // arrange
    var user = "default-floor-user";
    var skuId = createSku(user, "mtg", "low-market", "fetch-low", "0.10", "in_stock");
    seedCard("fetch-low", "0.10");
    createRepriceJob(user, "job1", null);

    // act
    jobsHandler.handleRequest(buildSqsEvent(user, "job1", "reprice"), null);

    // assert
    assertThat(getJob(user, "job1").getStatus()).isEqualTo("succeeded");
    assertThat(getJob(user, "job1").getProcessedCount()).isEqualTo(1);
    assertThat(getSku(user, skuId).getSuggestedPrice()).isEqualTo("0.25");
  }

  @Test
  void repriceShouldReadZeroFloorFromPricingItem() {
    // arrange
    var user = "zero-floor-user";
    var skuId = createSku(user, "mtg", "low-market", "fetch-low", "0.10", "in_stock");
    seedCard("fetch-low", "0.10");
    createRepriceJob(user, "job1", "0.00");

    // act
    jobsHandler.handleRequest(buildSqsEvent(user, "job1", "reprice"), null);

    // assert
    assertThat(getJob(user, "job1").getStatus()).isEqualTo("succeeded");
    assertThat(getSku(user, skuId).getSuggestedPrice()).isEqualTo("0.10");
  }

  @Test
  void repriceShouldReturnEmptySkuPageContinuationWithoutChangingCount() throws Exception {
    // arrange
    var user = "empty-sku-page-user";
    createRepriceJob(user, "job1", null);
    var inventoryService = mock(InventoryService.class);
    when(inventoryService.findSkus(any(), any()))
        .thenReturn(new FindSkusResponse(List.of(), "unexpected-continuation"));
    var processor = newRepriceJobProcessor(mock(PricingService.class), inventoryService);

    // act
    var result = processor.processBatch(user, getJob(user, "job1"));

    // assert
    assertThat(result).isEqualTo(new JobProcessor.SuccessJobResult(0, "unexpected-continuation"));
    verify(inventoryService)
        .findSkus(new RequestContext(user), new FindSkusRequest(null, null, null, 100, null));
  }

  @Test
  void repriceShouldPriceEveryEligibleSkuThroughTheService() throws Exception {
    // arrange
    var user = "cache-user";
    var firstSkuId = createSku(user, "mtg", "card-one", "shared-card", "1.00", "in_stock");
    var secondSkuId = createSku(user, "mtg", "card-two", "shared-card", "1.00", "in_stock");
    createRepriceJob(user, "job1", null);
    var pricingService = mock(PricingService.class);
    when(pricingService.getPrice(new RequestContext(user), new GetPriceRequest("shared-card", NM)))
        .thenReturn(new GetPriceResponse(new BigDecimal("1.00"), new BigDecimal("0.95")));

    // act
    newRepriceJobProcessor(pricingService).processBatch(user, getJob(user, "job1"));

    // assert
    verify(pricingService, times(2))
        .getPrice(new RequestContext(user), new GetPriceRequest("shared-card", NM));
    assertThat(getSku(user, firstSkuId).getSuggestedPrice()).isEqualTo("0.95");
    assertThat(getSku(user, secondSkuId).getSuggestedPrice()).isEqualTo("0.95");
  }

  @Test
  void repriceShouldContinueAfterEmptyUnitPage() throws Exception {
    // arrange
    var user = "empty-unit-page-user";
    var skuId = createSku(user, "mtg", "card", "fetch-card", "1.00", "in_stock");
    createRepriceJob(user, "job1", null);
    var inventoryService = mock(InventoryService.class);
    stubInventorySku(inventoryService, user, skuId);
    when(inventoryService.findUnits(any(), any()))
        .thenReturn(new FindUnitsResponse(List.of(), "removed-page"))
        .thenReturn(
            new FindUnitsResponse(
                List.of(
                    new Unit(
                        1,
                        "A0-1",
                        UnitStatus.IN_STOCK,
                        List.of(),
                        CREATED_AT.getEpochSecond(),
                        null,
                        null)),
                null));
    var pricingService = mock(PricingService.class);
    when(pricingService.getPrice(any(), any()))
        .thenReturn(new GetPriceResponse(new BigDecimal("1.00"), new BigDecimal("1.05")));

    // act
    newRepriceJobProcessor(pricingService, inventoryService)
        .processBatch(user, getJob(user, "job1"));

    // assert
    verify(inventoryService)
        .findUnits(new RequestContext(user), new FindUnitsRequest(skuId, 100, null));
    verify(inventoryService)
        .findUnits(new RequestContext(user), new FindUnitsRequest(skuId, 100, "removed-page"));
    verify(inventoryService)
        .updateSku(
            new RequestContext(user),
            new UpdateSkuRequest(skuId, new BigDecimal("1.05"), null, null));
  }

  @Test
  void repriceShouldNotWriteSkuWhenLaterUnitPageFails() throws Exception {
    // arrange
    var user = "later-unit-page-failure-user";
    var skuId = createSku(user, "mtg", "card", "fetch-card", "1.00", "in_stock");
    createRepriceJob(user, "job1", null);
    var inventoryService = mock(InventoryService.class);
    stubInventorySku(inventoryService, user, skuId);
    when(inventoryService.findUnits(any(), any()))
        .thenReturn(
            new FindUnitsResponse(
                List.of(
                    new Unit(
                        1,
                        "A0-1",
                        UnitStatus.IN_STOCK,
                        List.of(),
                        CREATED_AT.getEpochSecond(),
                        null,
                        null)),
                "next"))
        .thenThrow(new BadRequestException("unit page failed"));
    var pricingService = mock(PricingService.class);

    // act
    var result =
        newRepriceJobProcessor(pricingService, inventoryService)
            .processBatch(user, getJob(user, "job1"));

    // assert
    assertThat(result).isEqualTo(new JobProcessor.FailureJobResult("unit page failed"));
    assertThat(getSku(user, skuId).getSuggestedPrice()).isEqualTo("1.00");
    verify(inventoryService, times(0)).updateSku(any(), any());
    verifyNoInteractions(pricingService);
  }

  @Test
  void repriceShouldNotRequestPricingForOutOfStockSkus() {
    // arrange
    var user = "sold-user";
    createSku(user, "mtg", "sold-card", null, "1.00", "sold");
    createRepriceJob(user, "job1", null);
    var pricingService = mock(PricingService.class);

    // act
    newRepriceJobProcessor(pricingService).processBatch(user, getJob(user, "job1"));

    // assert
    verifyNoInteractions(pricingService);
  }

  @Test
  void repriceShouldReturnOtherPricingServiceErrorsAsTerminalFailures() throws Exception {
    // arrange
    var user = "unconfigured-user";
    createSku(user, "mtg", "card", "fetch-card", "1.00", "in_stock");
    createRepriceJob(user, "job1", null);
    var pricingService = mock(PricingService.class);
    when(pricingService.getPrice(new RequestContext(user), new GetPriceRequest("fetch-card", NM)))
        .thenThrow(new NotFoundException("FetchTCG account is not configured"));

    // act
    var result = newRepriceJobProcessor(pricingService).processBatch(user, getJob(user, "job1"));

    // assert
    assertThat(result)
        .isEqualTo(new JobProcessor.FailureJobResult("FetchTCG account is not configured"));
  }

  @Test
  void repriceShouldPropagateTemporaryFetchTcgFailureForQueueRetry() throws Exception {
    // arrange
    var user = "retry-user";
    createSku(user, "mtg", "card", "fetch-card", "1.00", "in_stock");
    createRepriceJob(user, "job1", null);
    var pricingService = mock(PricingService.class);
    when(pricingService.getPrice(new RequestContext(user), new GetPriceRequest("fetch-card", NM)))
        .thenThrow(new ServiceUnavailableException("FetchTCG is temporarily unavailable"));

    // act & assert
    assertThatThrownBy(
            () -> newRepriceJobProcessor(pricingService).processBatch(user, getJob(user, "job1")))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void repriceShouldCheckpointByCandidateCountAcrossHundredSkuBatches() {
    for (var candidateCount : List.of(0, 1, 99, 100, 101, 201)) {
      jobsQueue.reset();
      factory.fakeFetchTcgService().reset();
      var user = "batch-user-" + candidateCount;
      var jobId = "batch-job-" + candidateCount;

      // arrange
      createRepriceJob(user, jobId, "0.75");
      IntStream.range(0, candidateCount)
          .forEach(
              index ->
                  createSku(user, "mtg", String.format("card-%05d", index), null, "1.00", "sold"));
      var event = buildSqsEvent(user, jobId, "reprice");

      // act
      var invocations = 0;
      var job = getJob(user, jobId);
      while (!"succeeded".equals(job.getStatus())) {
        jobsHandler.handleRequest(event, null);
        invocations++;
        job = getJob(user, jobId);
        assertThat(job.getStatus()).isNotEqualTo("failed");
        assertThat(invocations).isLessThanOrEqualTo(3);
      }

      // assert
      assertThat(job.getProcessedCount()).isEqualTo(candidateCount);
      var expectedDeduplicationIds =
          IntStream.range(1, (candidateCount + 99) / 100)
              .mapToObj(index -> jobId + "#" + Math.min(index * 100, candidateCount))
              .toList();
      var actualDeduplicationIds =
          jobsQueue.getSends().stream()
              .map(FakeQueueClient.SentMessage::messageDeduplicationId)
              .toList();
      var extraFinalFullPageId =
          candidateCount > 0 && candidateCount % 100 == 0
              ? List.of(jobId + "#" + candidateCount)
              : List.<String>of();
      assertThat(actualDeduplicationIds)
          .isIn(
              expectedDeduplicationIds,
              Stream.concat(expectedDeduplicationIds.stream(), extraFinalFullPageId.stream())
                  .toList());
    }
  }

  @Test
  void repriceShouldContinueWhenDynamoDbReturnsAShortPageWithContinuation() {
    // arrange
    createRepriceJob("jordan", "job1", "0.75");
    var longSetName = "x".repeat(40000);
    IntStream.range(0, 35)
        .forEach(
            index ->
                createSku(
                    "jordan",
                    "mtg",
                    String.format("large-%05d", index),
                    null,
                    "1.00",
                    "sold",
                    longSetName));
    var event = buildSqsEvent("jordan", "job1", "reprice");

    // act
    jobsHandler.handleRequest(event, null);

    // assert
    var firstPageJob = getJob("jordan", "job1");
    assertThat(firstPageJob.getStatus()).isEqualTo("running");
    assertThat(firstPageJob.getProcessedCount()).isBetween(1, 34);
    assertThat(firstPageJob.getContinuation()).isNotNull();
    var firstPageCount = firstPageJob.getProcessedCount();
    while (!"succeeded".equals(getJob("jordan", "job1").getStatus())) {
      jobsHandler.handleRequest(event, null);
    }
    assertThat(getJob("jordan", "job1").getProcessedCount()).isEqualTo(35);
    assertThat(jobsQueue.getSends().get(0).messageDeduplicationId())
        .isEqualTo("job1#" + firstPageCount);
  }

  @Test
  void repriceShouldUsePriceFloorWhenNzMarketPriceIsMissing() {
    // arrange
    var skuId = createSku("jordan", "mtg", "001", "missing-market", "1.00", "in_stock");
    factory
        .fakeFetchTcgService()
        .seedCard(
            "missing-market",
            new GetCardResponse("missing-market", "Reprice test", null, Map.of()));
    createRepriceJob("jordan", "job1", "0.75");

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "reprice"), null);

    // assert
    var job = getJob("jordan", "job1");
    assertThat(job.getStatus()).isEqualTo("succeeded");
    assertThat(getSku("jordan", skuId).getSuggestedPrice()).isEqualTo("0.75");
    assertThat(getSku("jordan", skuId).getVersion()).isEqualTo(2);
    assertThat(getSku("jordan", skuId).getDirty()).isTrue();
  }

  private String createSku(
      String user,
      String game,
      String externalId,
      String fetchTcgCardId,
      String price,
      String unitStatus) {
    return createSku(user, game, externalId, fetchTcgCardId, price, unitStatus, "Test set");
  }

  private String createSku(
      String user,
      String game,
      String externalId,
      String fetchTcgCardId,
      String price,
      String unitStatus,
      String setName) {
    var externalSource = "mtg".equals(game) ? "scryfall" : "tcgplayer";
    var skuId = game + "#" + externalSource + "#" + externalId + "#normal#NM";
    var sku =
        SkuItem.create(
            user,
            skuId,
            game,
            externalId,
            "normal",
            "NM",
            "Reprice test",
            "TST",
            setName,
            "1",
            fetchTcgCardId,
            price);
    sku.setFetchtcgSetId(1);
    sku.setDirty(false);
    sku.setGsi1pk(SkuItem.formatUserPk(user) + "#CLEAN");
    sku.setGsi1sk(SkuItem.formatGsi1sk(skuId));
    skuTable.putItem(sku);
    if (unitStatus != null) {
      unitTable.putItem(UnitItem.create(user, game, skuId, 1, unitStatus, "import1", CREATED_AT));
    }
    return skuId;
  }

  private void createRepriceJob(String user, String jobId, String priceFloor) {
    if (priceFloor != null) {
      factory
          .dynamoDbClient()
          .putItem(
              PutItemRequest.builder()
                  .tableName(TcgInventoryTable.TABLE_NAME)
                  .item(
                      Map.of(
                          "pk", AttributeValue.fromS("USER#" + user),
                          "sk", AttributeValue.fromS("PRICING"),
                          "user", AttributeValue.fromS(user),
                          "price_floor", AttributeValue.fromS(priceFloor)))
                  .build());
    }
    var item = JobItem.create(user, jobId, "reprice", null, null, CREATED_AT);
    item.setProcessedCount(0);
    jobTable.putItem(item);
  }

  private void seedCard(String cardId, String marketPrice) {
    factory
        .fakeFetchTcgService()
        .seedCard(
            cardId,
            new GetCardResponse(cardId, "Reprice test", new BigDecimal(marketPrice), Map.of()));
  }

  private RepriceJobProcessor newRepriceJobProcessor(PricingService pricingService) {
    return new RepriceJobProcessor(
        factory.inventoryService(), pricingService, factory.gamesService());
  }

  private RepriceJobProcessor newRepriceJobProcessor(
      PricingService pricingService, InventoryService inventoryService) {
    return new RepriceJobProcessor(inventoryService, pricingService, factory.gamesService());
  }

  private void stubInventorySku(InventoryService inventoryService, String user, String skuId)
      throws Exception {
    var item = getSku(user, skuId);
    var sku =
        new Sku(
            item.getSkuId(),
            item.getGame(),
            item.getExternalId(),
            item.getName(),
            item.getSetCode(),
            item.getSetName(),
            item.getCollectorNumber(),
            item.getFinish(),
            Condition.valueOf(item.getCondition()),
            item.getSuggestedPrice() == null ? null : new BigDecimal(item.getSuggestedPrice()),
            item.getFetchtcgCardId(),
            item.getFetchtcgSetId(),
            item.getVersion());
    var ctx = new RequestContext(user);
    when(inventoryService.findSkus(any(), any()))
        .thenReturn(new FindSkusResponse(List.of(sku), null));
    when(inventoryService.getSku(ctx, new GetSkuRequest(skuId)))
        .thenReturn(new GetSkuResponse(sku));
  }

  private SkuItem getSku(String user, String skuId) {
    var key =
        Key.builder()
            .partitionValue(SkuItem.formatPk(user, skuId))
            .sortValue(SkuItem.formatSk())
            .build();
    return skuTable.getItem(request -> request.key(key).consistentRead(true));
  }

  private UnitItem getUnit(String user, String skuId) {
    var key =
        Key.builder()
            .partitionValue(UnitItem.formatPk(user, skuId))
            .sortValue(UnitItem.formatSk(1))
            .build();
    return unitTable.getItem(request -> request.key(key).consistentRead(true));
  }

  private JobItem getJob(String user, String jobId) {
    var key =
        Key.builder()
            .partitionValue(JobItem.formatPk(user))
            .sortValue(JobItem.formatSk(jobId))
            .build();
    return jobTable.getItem(request -> request.key(key).consistentRead(true));
  }

  private SQSEvent buildSqsEvent(String user, String jobId, String jobType) {
    var message = new JobMessage(user, jobId, jobType);
    var sqsMessage = new SQSEvent.SQSMessage();
    try {
      sqsMessage.setBody(objectMapper.writeValueAsString(message));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    var event = new SQSEvent();
    event.setRecords(List.of(sqsMessage));
    return event;
  }
}
