package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.queue.FakeQueueClient;
import com.jordansimsmith.time.FakeClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

@Testcontainers
public class JobsHandlerIntegrationTest {

  private FakeClock fakeClock;
  private FakeQueueClient<JobMessage> fakeJobsQueue;
  private FakeFetchTcgClient fakeFetchTcgClient;
  private ObjectMapper objectMapper;
  private DynamoDbTable<TcgInventoryItem> tcgInventoryTable;

  private JobsHandler jobsHandler;

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeAll
  static void setUpBeforeClass() {
    var factory = TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint());
    var table = factory.tcgInventoryTable();
    DynamoDbUtils.createTable(factory.dynamoDbClient(), table);
  }

  @BeforeEach
  void setUp() {
    var factory = TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint());

    fakeClock = factory.fakeClock();
    fakeJobsQueue = factory.fakeJobsQueue();
    fakeFetchTcgClient = factory.fakeFetchTcgClient();
    objectMapper = factory.objectMapper();
    tcgInventoryTable = factory.tcgInventoryTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());
    fakeJobsQueue.reset();
    fakeFetchTcgClient.reset();

    jobsHandler = new JobsHandler(factory);
  }

  @Test
  void appraiseShouldResolveIdentityAndKeep() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportWithRow("jordan", "import1", "dom", "168", "normal", "NM", "en");
    createJob("jordan", "job1", "appraise", "queued", "import1");

    fakeFetchTcgClient.seedSearchResult(
        2624,
        "Card 1",
        "normal",
        new FetchTcgClient.SearchCardsResponse(
            List.of(new FetchTcgClient.SearchCard("mtg_168_c_dom_normal"))));
    fakeFetchTcgClient.seedCard(
        "mtg_168_c_dom_normal",
        new FetchTcgClient.GetCardResponse(
            "mtg_168_c_dom_normal",
            "Card 1",
            Map.of("NZ", new FetchTcgClient.PricingData(new BigDecimal("1.50")))));
    fakeFetchTcgClient.seedListings(
        "mtg_168_c_dom_normal",
        new FetchTcgClient.GetCardListingsResponse(
            List.of(
                new FetchTcgClient.CardListing(1, "raw-nm", new BigDecimal("1.20"), "rival1"))));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    var row = getRow("jordan", "import1", 1);
    assertThat(row.getDecision()).isEqualTo("keep");
    assertThat(row.getMarketPrice()).isEqualTo("1.50");
    assertThat(row.getSuggestedPrice()).isNotNull();

    var importItem = getImport("jordan", "import1");
    assertThat(importItem.getStatus()).isEqualTo("review");
  }

  @Test
  void appraiseShouldDiscardBelowThreshold() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportWithRow("jordan", "import1", "dom", "168", "normal", "NM", "en");
    createJob("jordan", "job1", "appraise", "queued", "import1");

    fakeFetchTcgClient.seedSearchResult(
        2624,
        "Card 1",
        "normal",
        new FetchTcgClient.SearchCardsResponse(
            List.of(new FetchTcgClient.SearchCard("mtg_168_c_dom_normal"))));
    fakeFetchTcgClient.seedCard(
        "mtg_168_c_dom_normal",
        new FetchTcgClient.GetCardResponse(
            "mtg_168_c_dom_normal",
            "Card 1",
            Map.of("NZ", new FetchTcgClient.PricingData(new BigDecimal("0.10")))));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    var row = getRow("jordan", "import1", 1);
    assertThat(row.getDecision()).isEqualTo("discard");
    assertThat(row.getDecisionReason()).isEqualTo("below threshold");
    assertThat(row.getMarketPrice()).isEqualTo("0.10");

    var importItem = getImport("jordan", "import1");
    assertThat(importItem.getStatus()).isEqualTo("review");
  }

  @Test
  void appraiseShouldReviewNonEnglish() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportWithRow("jordan", "import1", "dom", "168", "normal", "NM", "ja");
    createJob("jordan", "job1", "appraise", "queued", "import1");

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    var row = getRow("jordan", "import1", 1);
    assertThat(row.getDecision()).isEqualTo("review");
    assertThat(row.getDecisionReason()).isEqualTo("non-english");
  }

  @Test
  void appraiseShouldReviewUnmappedSet() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportWithRow("jordan", "import1", "zzz_unmapped", "1", "normal", "NM", "en");
    createJob("jordan", "job1", "appraise", "queued", "import1");

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    var row = getRow("jordan", "import1", 1);
    assertThat(row.getDecision()).isEqualTo("review");
    assertThat(row.getDecisionReason()).isEqualTo("unmapped set");
  }

  @Test
  void appraiseShouldReviewUnresolvable() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportWithRow("jordan", "import1", "dom", "999", "normal", "NM", "en");
    createJob("jordan", "job1", "appraise", "queued", "import1");

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    var row = getRow("jordan", "import1", 1);
    assertThat(row.getDecision()).isEqualTo("review");
    assertThat(row.getDecisionReason()).isEqualTo("unresolvable");
  }

  @Test
  void appraiseShouldDedupeWithinBatch() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportWithRows(
        "jordan",
        "import1",
        List.of(
            new RowSpec("dom", "168", "normal", "NM", "en", "scryfall-1"),
            new RowSpec("dom", "168", "normal", "LP", "en", "scryfall-1")));
    createJob("jordan", "job1", "appraise", "queued", "import1");

    fakeFetchTcgClient.seedSearchResult(
        2624,
        "Card 1",
        "normal",
        new FetchTcgClient.SearchCardsResponse(
            List.of(new FetchTcgClient.SearchCard("mtg_168_c_dom_normal"))));
    fakeFetchTcgClient.seedCard(
        "mtg_168_c_dom_normal",
        new FetchTcgClient.GetCardResponse(
            "mtg_168_c_dom_normal",
            "Card 1",
            Map.of("NZ", new FetchTcgClient.PricingData(new BigDecimal("1.50")))));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    assertThat(fakeFetchTcgClient.getSearchCallCount()).isEqualTo(1);

    var row1 = getRow("jordan", "import1", 1);
    assertThat(row1.getDecision()).isEqualTo("keep");
    var row2 = getRow("jordan", "import1", 2);
    assertThat(row2.getDecision()).isEqualTo("keep");
  }

  @Test
  void appraiseShouldCheckpointAndContinue() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    int totalRows = AppraiseJobProcessor.BATCH_SIZE + 2;
    createImportWithNRows("jordan", "import1", totalRows);
    createJob("jordan", "job1", "appraise", "queued", "import1");

    seedDefaultCardForDom168();

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    var jobItem = getJob("jordan", "job1");
    assertThat(jobItem.getStatus()).isEqualTo("running");
    assertThat(jobItem.getContinuation()).isEqualTo(AppraiseJobProcessor.BATCH_SIZE);
    assertThat(fakeJobsQueue.getSends()).hasSize(1);
    var continuationSend = fakeJobsQueue.getSends().get(0);
    assertThat(continuationSend.messageGroupId()).isEqualTo("jordan");
    assertThat(continuationSend.messageDeduplicationId())
        .isEqualTo("job1#" + AppraiseJobProcessor.BATCH_SIZE);

    // act - second batch
    fakeJobsQueue.reset();
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    var completedJob = getJob("jordan", "job1");
    assertThat(completedJob.getStatus()).isEqualTo("succeeded");
    assertThat(completedJob.getProcessedCount()).isEqualTo(totalRows);
    assertThat(fakeJobsQueue.getMessages()).isEmpty();

    var importItem = getImport("jordan", "import1");
    assertThat(importItem.getStatus()).isEqualTo("review");
  }

  @Test
  void duplicateDeliveryShouldNoOpWhenSucceeded() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));

    var jobItem =
        TcgInventoryItem.createJob(
            "jordan", "job1", "appraise", "import1", Instant.ofEpochSecond(1700000000));
    jobItem.setStatus("succeeded");
    jobItem.setProcessedCount(5);
    jobItem.setContinuation(5);
    jobItem.setUpdatedAt(Instant.ofEpochSecond(1700000100));
    tcgInventoryTable.putItem(jobItem);

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    var updatedJob = getJob("jordan", "job1");
    assertThat(updatedJob.getStatus()).isEqualTo("succeeded");
    assertThat(updatedJob.getUpdatedAt()).isEqualTo(Instant.ofEpochSecond(1700000100));
    assertThat(fakeJobsQueue.getMessages()).isEmpty();
  }

  @Test
  void publishOrderPhaseShouldReserveUnitsForNewOffer() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createSkuWithUnits("jordan", "scryfall-1#normal#NM", 1001, 3);

    fakeFetchTcgClient.seedSellerOffers(
        List.of(
            new FetchTcgClient.SellerOffer(
                83663,
                "ACCEPTED",
                null,
                "2026-08-11T04:42:12.476+0000",
                "PICKUP",
                new BigDecimal("3.33"),
                List.of(
                    new FetchTcgClient.OfferItem(
                        new FetchTcgClient.OfferListing(1001, "raw-nm"),
                        2,
                        new BigDecimal("1.50"))))));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var updatedJob = getJob("jordan", "job1");
    assertThat(updatedJob.getStatus()).isEqualTo("succeeded");

    var order = getOrder("jordan", "83663");
    assertThat(order).isNotNull();
    assertThat(order.getStatus()).isEqualTo("awaiting_payment");
    assertThat(order.getDeliveryMode()).isEqualTo("PICKUP");
    assertThat(order.getTotalPrice()).isEqualTo("3.33");
    assertThat(order.getFetchtcgStatus()).isEqualTo("ACCEPTED");
    assertThat(order.getLines()).contains("scryfall-1#normal#NM");

    var sku = getSku("jordan", "scryfall-1#normal#NM");
    assertThat(sku.getDirty()).isFalse();
    assertThat(sku.getLastPublishedQuantity()).isEqualTo(1);

    var units = getUnits("jordan", "scryfall-1#normal#NM");
    var reserved = units.stream().filter(u -> "reserved".equals(u.getStatus())).toList();
    assertThat(reserved).hasSize(2);
    assertThat(reserved.get(0).getOrderId()).isEqualTo("83663");

    var audit = getAuditEntries("jordan");
    assertThat(audit.stream().anyMatch(a -> "reserve".equals(a.getEventType()))).isTrue();
  }

  @Test
  void publishOrderPhaseShouldAdvanceToPickOnPayment() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createExistingOrder("jordan", "83663", "awaiting_payment");

    fakeFetchTcgClient.seedSellerOffers(
        List.of(
            new FetchTcgClient.SellerOffer(
                83663,
                "ACCEPTED",
                "SEND_PICKUP_ADDRESS",
                "2026-08-11T04:42:12.476+0000",
                "PICKUP",
                new BigDecimal("3.33"),
                List.of())));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order.getStatus()).isEqualTo("to_pick");
    assertThat(order.getFetchtcgCurrentAction()).isEqualTo("SEND_PICKUP_ADDRESS");
  }

  @Test
  void publishOrderPhaseShouldBeIdempotentOnReprocessing() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createExistingOrder("jordan", "83663", "awaiting_payment");

    fakeFetchTcgClient.seedSellerOffers(
        List.of(
            new FetchTcgClient.SellerOffer(
                83663,
                "ACCEPTED",
                null,
                "2026-08-11T04:42:12.476+0000",
                "PICKUP",
                new BigDecimal("3.33"),
                List.of())));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order.getStatus()).isEqualTo("awaiting_payment");
  }

  @Test
  void publishOrderPhaseShouldFlagInsufficientStock() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createSkuWithUnits("jordan", "scryfall-1#normal#NM", 1001, 1);

    fakeFetchTcgClient.seedSellerOffers(
        List.of(
            new FetchTcgClient.SellerOffer(
                83663,
                "ACCEPTED",
                null,
                "2026-08-11T04:42:12.476+0000",
                "PICKUP",
                new BigDecimal("3.33"),
                List.of(
                    new FetchTcgClient.OfferItem(
                        new FetchTcgClient.OfferListing(1001, "raw-nm"),
                        3,
                        new BigDecimal("1.50"))))));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order).isNotNull();
    assertThat(order.getStatus()).isEqualTo("flagged");
  }

  @Test
  void publishOrderPhaseShouldSkipOffersBeforeCutoff() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createSkuWithUnits("jordan", "scryfall-1#normal#NM", 1001, 3);
    createTrackOrdersAfter("jordan", Instant.parse("2026-08-15T00:00:00Z"));

    fakeFetchTcgClient.seedSellerOffers(
        List.of(
            new FetchTcgClient.SellerOffer(
                83663,
                "ACCEPTED",
                null,
                "2026-08-11T04:42:12.476+0000",
                "PICKUP",
                new BigDecimal("3.33"),
                List.of(
                    new FetchTcgClient.OfferItem(
                        new FetchTcgClient.OfferListing(1001, "raw-nm"),
                        2,
                        new BigDecimal("1.50"))))));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order).isNull();

    var units = getUnits("jordan", "scryfall-1#normal#NM");
    assertThat(units.stream().allMatch(u -> "in_stock".equals(u.getStatus()))).isTrue();
  }

  @Test
  void publishOrderPhaseShouldSkipOffersWithNullAcceptedAtWhenCutoffSet() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createSkuWithUnits("jordan", "scryfall-1#normal#NM", 1001, 3);
    createTrackOrdersAfter("jordan", Instant.parse("2026-08-01T00:00:00Z"));

    fakeFetchTcgClient.seedSellerOffers(
        List.of(
            new FetchTcgClient.SellerOffer(
                83663,
                "ACCEPTED",
                null,
                null,
                "PICKUP",
                new BigDecimal("3.33"),
                List.of(
                    new FetchTcgClient.OfferItem(
                        new FetchTcgClient.OfferListing(1001, "raw-nm"),
                        2,
                        new BigDecimal("1.50"))))));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order).isNull();
  }

  @Test
  void publishOrderPhaseShouldCreateOrderWhenCutoffNotSet() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createSkuWithUnits("jordan", "scryfall-1#normal#NM", 1001, 3);

    fakeFetchTcgClient.seedSellerOffers(
        List.of(
            new FetchTcgClient.SellerOffer(
                83663,
                "ACCEPTED",
                null,
                "2026-08-11T04:42:12.476+0000",
                "PICKUP",
                new BigDecimal("3.33"),
                List.of(
                    new FetchTcgClient.OfferItem(
                        new FetchTcgClient.OfferListing(1001, "raw-nm"),
                        2,
                        new BigDecimal("1.50"))))));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order).isNotNull();
    assertThat(order.getStatus()).isEqualTo("awaiting_payment");
  }

  @Test
  void publishOrderPhaseShouldCreateOrderAcceptedAfterCutoff() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createSkuWithUnits("jordan", "scryfall-1#normal#NM", 1001, 3);
    createTrackOrdersAfter("jordan", Instant.parse("2026-08-10T00:00:00Z"));

    fakeFetchTcgClient.seedSellerOffers(
        List.of(
            new FetchTcgClient.SellerOffer(
                83663,
                "ACCEPTED",
                null,
                "2026-08-11T04:42:12.476+0000",
                "PICKUP",
                new BigDecimal("3.33"),
                List.of(
                    new FetchTcgClient.OfferItem(
                        new FetchTcgClient.OfferListing(1001, "raw-nm"),
                        2,
                        new BigDecimal("1.50"))))));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order).isNotNull();
    assertThat(order.getStatus()).isEqualTo("awaiting_payment");
  }

  @Test
  void publishPhaseShouldCreateListingForDirtySku() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createDirtySkuWithUnits("jordan", "scryfall-1#normal#NM", 2, "1.50");

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var sku = getSku("jordan", "scryfall-1#normal#NM");
    assertThat(sku.getDirty()).isFalse();
    assertThat(sku.getFetchtcgListingId()).isNotNull();
    assertThat(sku.getLastPublishedQuantity()).isEqualTo(2);
    assertThat(sku.getLastPublishedPrice()).isEqualTo("1.50");
    assertThat(sku.getLastPublishedAt()).isEqualTo(Instant.ofEpochSecond(1700000000));

    assertThat(fakeFetchTcgClient.getUpsertCalls()).hasSize(1);
    var upsert = fakeFetchTcgClient.getUpsertCalls().get(0);
    assertThat(upsert.cardId()).isEqualTo("mtg_168_c_dom_normal");
    assertThat(upsert.condition()).isEqualTo("raw-nm");
    assertThat(upsert.quantity()).isEqualTo(2);
    assertThat(upsert.price()).isEqualByComparingTo("1.50");
  }

  @Test
  void publishPhaseShouldUpdateListingForExistingSku() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createDirtySkuWithUnits("jordan", "scryfall-1#normal#NM", 3, "2.00");
    var sku = getSku("jordan", "scryfall-1#normal#NM");
    sku.setFetchtcgListingId(975737);
    sku.setLastPublishedQuantity(1);
    sku.setLastPublishedPrice("1.80");
    tcgInventoryTable.putItem(sku);

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var updated = getSku("jordan", "scryfall-1#normal#NM");
    assertThat(updated.getDirty()).isFalse();
    assertThat(updated.getLastPublishedQuantity()).isEqualTo(3);
    assertThat(updated.getLastPublishedPrice()).isEqualTo("2.00");

    assertThat(fakeFetchTcgClient.getUpsertCalls()).hasSize(1);
    var upsert = fakeFetchTcgClient.getUpsertCalls().get(0);
    assertThat(upsert.quantity()).isEqualTo(3);
    assertThat(upsert.price()).isEqualByComparingTo("2.00");
  }

  @Test
  void publishPhaseShouldDelistAtZero() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createDirtySkuWithUnits("jordan", "scryfall-1#normal#NM", 0, "1.50");
    var sku = getSku("jordan", "scryfall-1#normal#NM");
    sku.setFetchtcgListingId(975737);
    sku.setLastPublishedQuantity(1);
    sku.setLastPublishedPrice("1.50");
    tcgInventoryTable.putItem(sku);

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var updated = getSku("jordan", "scryfall-1#normal#NM");
    assertThat(updated.getDirty()).isFalse();
    assertThat(updated.getFetchtcgListingId()).isNull();
    assertThat(updated.getLastPublishedQuantity()).isNull();
    assertThat(updated.getLastPublishedPrice()).isNull();

    assertThat(fakeFetchTcgClient.getDeleteCalls()).containsExactly(975737);
    assertThat(fakeFetchTcgClient.getUpsertCalls()).isEmpty();
  }

  @Test
  void publishPhaseShouldNotClearDirtyWhenConditionFails() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createDirtySkuWithUnits("jordan", "scryfall-1#normal#NM", 2, "1.50");

    // set dirty=false directly to simulate a race where the condition check
    // (dirty = true AND version = :captured) fails at write time
    var sku = getSku("jordan", "scryfall-1#normal#NM");
    sku.setDirty(false);
    tcgInventoryTable.putItem(sku);

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert - the upsert was called (FetchTCG got the update)
    assertThat(fakeFetchTcgClient.getUpsertCalls()).hasSize(1);

    // but the listing snapshot was NOT written (condition failed)
    var updated = getSku("jordan", "scryfall-1#normal#NM");
    assertThat(updated.getFetchtcgListingId()).isNull();
    assertThat(updated.getLastPublishedQuantity()).isNull();
  }

  private void createDirtySkuWithUnits(
      String user, String skuId, int unitCount, String suggestedPrice) {
    var parts = skuId.split("#");
    var skuItem =
        TcgInventoryItem.createSku(
            user,
            skuId,
            parts[0],
            parts[1],
            parts[2],
            "Test Card",
            "dom",
            "Dominaria",
            "168",
            "mtg_168_c_dom_normal",
            suggestedPrice);
    tcgInventoryTable.putItem(skuItem);

    for (int i = 1; i <= unitCount; i++) {
      var unit =
          TcgInventoryItem.createUnit(
              user, skuId, i, "in_stock", "import1", Instant.ofEpochSecond(1700000000));
      tcgInventoryTable.putItem(unit);
    }
  }

  private void createPublishJob(String user, String jobId) {
    var jobItem =
        TcgInventoryItem.createJob(user, jobId, "publish", null, Instant.ofEpochSecond(1700000000));
    tcgInventoryTable.putItem(jobItem);
  }

  private void createSkuWithUnits(String user, String skuId, int fetchtcgListingId, int unitCount) {
    var parts = skuId.split("#");
    var skuItem =
        TcgInventoryItem.createSku(
            user,
            skuId,
            parts[0],
            parts[1],
            parts[2],
            "Test Card",
            "dom",
            "Dominaria",
            "168",
            "mtg_168_c_dom_normal",
            "1.50");
    skuItem.setDirty(false);
    skuItem.setGsi1pk(TcgInventoryItem.USER_PREFIX + user + "#CLEAN");
    skuItem.setFetchtcgListingId(fetchtcgListingId);
    tcgInventoryTable.putItem(skuItem);

    for (int i = 1; i <= unitCount; i++) {
      var unit =
          TcgInventoryItem.createUnit(
              user, skuId, i, "in_stock", "import1", Instant.ofEpochSecond(1700000000));
      tcgInventoryTable.putItem(unit);
    }
  }

  private void createTrackOrdersAfter(String user, Instant trackOrdersAfter) {
    var settingsItem = new TcgInventoryItem();
    settingsItem.setPk(TcgInventoryItem.formatUserPk(user));
    settingsItem.setSk(TcgInventoryItem.formatSettingsSk());
    settingsItem.setTrackOrdersAfter(trackOrdersAfter);
    tcgInventoryTable.putItem(settingsItem);
  }

  private void createExistingOrder(String user, String offerId, String status) {
    var order =
        TcgInventoryItem.createOrder(
            user,
            offerId,
            status,
            "ACCEPTED",
            null,
            "PICKUP",
            "3.33",
            "[]",
            Instant.ofEpochSecond(1700000000));
    tcgInventoryTable.putItem(order);
  }

  private TcgInventoryItem getOrder(String user, String offerId) {
    return tcgInventoryTable.getItem(
        Key.builder()
            .partitionValue(TcgInventoryItem.formatUserPk(user))
            .sortValue(TcgInventoryItem.formatOrderSk(offerId))
            .build());
  }

  private TcgInventoryItem getSku(String user, String skuId) {
    return tcgInventoryTable.getItem(
        Key.builder()
            .partitionValue(TcgInventoryItem.formatSkuPk(user, skuId))
            .sortValue(TcgInventoryItem.formatSkuSk())
            .build());
  }

  private List<TcgInventoryItem> getUnits(String user, String skuId) {
    var results = new ArrayList<TcgInventoryItem>();
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(TcgInventoryItem.formatSkuPk(user, skuId))
                        .sortValue(TcgInventoryItem.UNIT_PREFIX)
                        .build()))
            .build();
    tcgInventoryTable.query(request).items().forEach(results::add);
    return results;
  }

  private List<TcgInventoryItem> getAuditEntries(String user) {
    var results = new ArrayList<TcgInventoryItem>();
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.keyEqualTo(
                    Key.builder().partitionValue(TcgInventoryItem.formatAuditPk(user)).build()))
            .build();
    tcgInventoryTable.query(request).items().forEach(results::add);
    return results;
  }

  private void seedDefaultCardForDom168() {
    fakeFetchTcgClient.seedSearchResult(
        2624,
        "Card 1",
        "normal",
        new FetchTcgClient.SearchCardsResponse(
            List.of(new FetchTcgClient.SearchCard("mtg_168_c_dom_normal"))));
    fakeFetchTcgClient.seedCard(
        "mtg_168_c_dom_normal",
        new FetchTcgClient.GetCardResponse(
            "mtg_168_c_dom_normal",
            "Card 1",
            Map.of("NZ", new FetchTcgClient.PricingData(new BigDecimal("1.50")))));
  }

  private void createImportWithRow(
      String user,
      String importId,
      String setCode,
      String collectorNumber,
      String finish,
      String condition,
      String language) {
    createImportWithRows(
        user,
        importId,
        List.of(new RowSpec(setCode, collectorNumber, finish, condition, language, "scryfall-1")));
  }

  private void createImportWithRows(String user, String importId, List<RowSpec> rows) {
    var importItem =
        TcgInventoryItem.createImport(
            user, importId, "test.csv", rows.size(), null, Instant.ofEpochSecond(1700000000));
    tcgInventoryTable.putItem(importItem);

    for (int i = 0; i < rows.size(); i++) {
      var spec = rows.get(i);
      var rowItem =
          TcgInventoryItem.createImportRow(
              user,
              importId,
              i + 1,
              "Card " + (i + 1),
              spec.setCode(),
              "Test Set",
              spec.collectorNumber(),
              spec.finish(),
              spec.condition(),
              spec.scryfallId(),
              spec.language());
      tcgInventoryTable.putItem(rowItem);
    }
  }

  private void createImportWithNRows(String user, String importId, int rowCount) {
    var importItem =
        TcgInventoryItem.createImport(
            user, importId, "test.csv", rowCount, null, Instant.ofEpochSecond(1700000000));
    tcgInventoryTable.putItem(importItem);

    for (int i = 1; i <= rowCount; i++) {
      var rowItem =
          TcgInventoryItem.createImportRow(
              user,
              importId,
              i,
              "Card " + i,
              "dom",
              "Dominaria",
              "168",
              "normal",
              "NM",
              "scryfall-" + i,
              "en");
      tcgInventoryTable.putItem(rowItem);
    }
  }

  private TcgInventoryItem createJob(
      String user, String jobId, String jobType, String status, String importId) {
    var jobItem =
        TcgInventoryItem.createJob(
            user, jobId, jobType, importId, Instant.ofEpochSecond(1700000000));
    if (!"queued".equals(status)) {
      jobItem.setStatus(status);
    }
    tcgInventoryTable.putItem(jobItem);
    return jobItem;
  }

  private TcgInventoryItem getRow(String user, String importId, int position) {
    return tcgInventoryTable.getItem(
        Key.builder()
            .partitionValue(TcgInventoryItem.formatImportRowPk(user, importId))
            .sortValue(TcgInventoryItem.formatImportRowSk(position))
            .build());
  }

  private TcgInventoryItem getImport(String user, String importId) {
    return tcgInventoryTable.getItem(
        Key.builder()
            .partitionValue(TcgInventoryItem.formatUserPk(user))
            .sortValue(TcgInventoryItem.formatImportSk(importId))
            .build());
  }

  private TcgInventoryItem getJob(String user, String jobId) {
    return tcgInventoryTable.getItem(
        Key.builder()
            .partitionValue(TcgInventoryItem.formatUserPk(user))
            .sortValue(TcgInventoryItem.formatJobSk(jobId))
            .build());
  }

  private SQSEvent buildSqsEvent(String user, String jobId, String jobType) {
    try {
      var message = new JobMessage(user, jobId, jobType);
      var body = objectMapper.writeValueAsString(message);
      var sqsMessage = new SQSEvent.SQSMessage();
      sqsMessage.setBody(body);
      var event = new SQSEvent();
      event.setRecords(List.of(sqsMessage));
      return event;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  record RowSpec(
      String setCode,
      String collectorNumber,
      String finish,
      String condition,
      String language,
      String scryfallId) {}
}
