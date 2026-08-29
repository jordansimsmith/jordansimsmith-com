package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.queue.FakeQueueClient;
import com.jordansimsmith.time.FakeClock;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
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

  private static final URI UNUSED_S3_ENDPOINT = URI.create("http://localhost:1");

  @BeforeAll
  static void setUpBeforeClass() {
    var factory =
        TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint(), UNUSED_S3_ENDPOINT);
    var table = factory.tcgInventoryTable();
    DynamoDbUtils.createTable(factory.dynamoDbClient(), table);
  }

  @BeforeEach
  void setUp() {
    var factory =
        TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint(), UNUSED_S3_ENDPOINT);

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
            Map.of("NZ", new FetchTcgClient.PricingData(new BigDecimal("1.50"))),
            new FetchTcgClient.ExternalReferences("scryfall-1")));
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
            Map.of("NZ", new FetchTcgClient.PricingData(new BigDecimal("0.10"))),
            new FetchTcgClient.ExternalReferences("scryfall-1")));

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
  void appraiseShouldResolveVariantPrintingByScryfallId() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportWithRow("jordan", "import1", "dom", "410", "normal", "NM", "en");
    createJob("jordan", "job1", "appraise", "queued", "import1");

    fakeFetchTcgClient.seedSearchResult(
        2624,
        "Card 1",
        "normal",
        new FetchTcgClient.SearchCardsResponse(
            List.of(
                new FetchTcgClient.SearchCard("mtg_168_c_dom_normal"),
                new FetchTcgClient.SearchCard("mtg_410_c_dom_B_normal"))));
    fakeFetchTcgClient.seedCard(
        "mtg_168_c_dom_normal",
        new FetchTcgClient.GetCardResponse(
            "mtg_168_c_dom_normal",
            "Card 1",
            Map.of("NZ", new FetchTcgClient.PricingData(new BigDecimal("1.50"))),
            new FetchTcgClient.ExternalReferences("scryfall-other")));
    fakeFetchTcgClient.seedCard(
        "mtg_410_c_dom_B_normal",
        new FetchTcgClient.GetCardResponse(
            "mtg_410_c_dom_B_normal",
            "Card 1 (Borderless)",
            Map.of("NZ", new FetchTcgClient.PricingData(new BigDecimal("27.43"))),
            new FetchTcgClient.ExternalReferences("scryfall-1")));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    var row = getRow("jordan", "import1", 1);
    assertThat(row.getDecision()).isEqualTo("keep");
    assertThat(row.getFetchtcgCardId()).isEqualTo("mtg_410_c_dom_B_normal");
    assertThat(row.getMarketPrice()).isEqualTo("27.43");
  }

  @Test
  void appraiseShouldReviewWhenNoCandidateMatchesScryfallId() {
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
            Map.of("NZ", new FetchTcgClient.PricingData(new BigDecimal("1.50"))),
            new FetchTcgClient.ExternalReferences("scryfall-other")));

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
            Map.of("NZ", new FetchTcgClient.PricingData(new BigDecimal("1.50"))),
            new FetchTcgClient.ExternalReferences("scryfall-1")));

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
  void publishShouldCheckpointAndContinue() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    int totalSkus = ListingPhaseProcessor.BATCH_SIZE + 2;
    createPublishJob("jordan", "job1");
    for (int i = 1; i <= totalSkus; i++) {
      createDirtySkuWithUnit("jordan", "scryfall-" + i + "#normal#NM", i);
    }

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var jobItem = getJob("jordan", "job1");
    assertThat(jobItem.getStatus()).isEqualTo("running");
    assertThat(jobItem.getContinuation()).isEqualTo(ListingPhaseProcessor.BATCH_SIZE);
    assertThat(fakeFetchTcgClient.getUpsertCalls()).hasSize(ListingPhaseProcessor.BATCH_SIZE);
    assertThat(fakeJobsQueue.getSends()).hasSize(1);
    var continuationSend = fakeJobsQueue.getSends().get(0);
    assertThat(continuationSend.messageGroupId()).isEqualTo("jordan");
    assertThat(continuationSend.messageDeduplicationId())
        .isEqualTo("job1#" + ListingPhaseProcessor.BATCH_SIZE);

    // act - second slice
    fakeJobsQueue.reset();
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var completedJob = getJob("jordan", "job1");
    assertThat(completedJob.getStatus()).isEqualTo("succeeded");
    assertThat(completedJob.getProcessedCount()).isEqualTo(totalSkus);
    assertThat(fakeFetchTcgClient.getUpsertCalls()).hasSize(totalSkus);
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
                        new FetchTcgClient.OfferListing(1001, "raw-nm", new BigDecimal("2.00")),
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
    var orderLines = OrderLines.parse(order.getLines(), objectMapper);
    assertThat(orderLines).hasSize(1);
    assertThat(orderLines.get(0).price()).isEqualTo("1.50");
    assertThat(orderLines.get(0).listedPrice()).isEqualTo("2.00");
    assertThat(orderLines.get(0).quantity()).isEqualTo(2);

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

    var paymentAudits =
        getAuditEntries("jordan").stream().filter(a -> "payment".equals(a.getEventType())).toList();
    assertThat(paymentAudits).hasSize(1);
    assertThat(paymentAudits.get(0).getOrderId()).isEqualTo("83663");
  }

  @Test
  void publishOrderPhaseShouldAdvanceToPickOnTrackingCode() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createExistingOrder("jordan", "91329", "awaiting_payment");

    fakeFetchTcgClient.seedSellerOffers(
        List.of(
            new FetchTcgClient.SellerOffer(
                91329,
                "ACCEPTED",
                "SEND_TRACKING_CODE",
                "2026-08-29T03:03:55.019+0000",
                "DELIVERY",
                new BigDecimal("61.50"),
                List.of())));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var order = getOrder("jordan", "91329");
    assertThat(order.getStatus()).isEqualTo("to_pick");
    assertThat(order.getFetchtcgCurrentAction()).isEqualTo("SEND_TRACKING_CODE");

    var paymentAudits =
        getAuditEntries("jordan").stream().filter(a -> "payment".equals(a.getEventType())).toList();
    assertThat(paymentAudits).hasSize(1);
    assertThat(paymentAudits.get(0).getOrderId()).isEqualTo("91329");
  }

  @Test
  void publishOrderPhaseShouldNotAdvanceWhenAwaitingPayment() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createExistingOrder("jordan", "91101", "awaiting_payment");

    fakeFetchTcgClient.seedSellerOffers(
        List.of(
            new FetchTcgClient.SellerOffer(
                91101,
                "ACCEPTED",
                "AWAITING_PAYMENT",
                "2026-08-28T10:50:05.986+0000",
                "DELIVERY",
                new BigDecimal("5.00"),
                List.of())));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var order = getOrder("jordan", "91101");
    assertThat(order.getStatus()).isEqualTo("awaiting_payment");
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
                        new FetchTcgClient.OfferListing(1001, "raw-nm", new BigDecimal("2.00")),
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
                        new FetchTcgClient.OfferListing(1001, "raw-nm", new BigDecimal("2.00")),
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
                        new FetchTcgClient.OfferListing(1001, "raw-nm", new BigDecimal("2.00")),
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
                        new FetchTcgClient.OfferListing(1001, "raw-nm", new BigDecimal("2.00")),
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
                        new FetchTcgClient.OfferListing(1001, "raw-nm", new BigDecimal("2.00")),
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
  void publishOrderPhaseShouldReserveLargeOfferAcrossTransactions() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");

    var offerItems = new ArrayList<FetchTcgClient.OfferItem>();
    for (int i = 1; i <= 60; i++) {
      createSkuWithUnitAtSequence("jordan", "scryfall-" + i + "#normal#NM", 2000 + i, i);
      offerItems.add(
          new FetchTcgClient.OfferItem(
              new FetchTcgClient.OfferListing(2000 + i, "raw-nm", new BigDecimal("0.50")),
              1,
              new BigDecimal("0.50")));
    }

    fakeFetchTcgClient.seedSellerOffers(
        List.of(
            new FetchTcgClient.SellerOffer(
                91329,
                "ACCEPTED",
                null,
                "2026-08-11T04:42:12.476+0000",
                "DELIVERY",
                new BigDecimal("30.00"),
                offerItems)));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var jobItem = getJob("jordan", "job1");
    assertThat(jobItem.getStatus()).isEqualTo("succeeded");

    var order = getOrder("jordan", "91329");
    assertThat(order).isNotNull();
    assertThat(order.getStatus()).isEqualTo("awaiting_payment");
    var orderLines = OrderLines.parse(order.getLines(), objectMapper);
    assertThat(orderLines).hasSize(60);
    var allocated =
        orderLines.stream().flatMap(l -> l.allocatedSequenceNumbers().stream()).toList();
    assertThat(allocated)
        .containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, 60).boxed().toList());

    for (int i = 1; i <= 60; i++) {
      var units = getUnits("jordan", "scryfall-" + i + "#normal#NM");
      assertThat(units).hasSize(1);
      assertThat(units.get(0).getStatus()).isEqualTo("reserved");
      assertThat(units.get(0).getOrderId()).isEqualTo("91329");
    }

    var reserveAudits =
        getAuditEntries("jordan").stream().filter(a -> "reserve".equals(a.getEventType())).toList();
    assertThat(reserveAudits).hasSize(1);
  }

  @Test
  void publishOrderPhaseShouldReclaimUnitsReservedByCrashedRun() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createSkuWithUnits("jordan", "scryfall-1#normal#NM", 1001, 3);

    // simulate a run that crashed after reserving units but before writing the order
    var sku = getSku("jordan", "scryfall-1#normal#NM");
    sku.setDirty(true);
    sku.setGsi1pk(TcgInventoryItem.formatGsi1pk("jordan"));
    tcgInventoryTable.putItem(sku);
    for (int sequenceNumber : new int[] {1, 2}) {
      var unit =
          tcgInventoryTable.getItem(
              Key.builder()
                  .partitionValue(TcgInventoryItem.formatSkuPk("jordan", "scryfall-1#normal#NM"))
                  .sortValue(TcgInventoryItem.formatUnitSk(sequenceNumber))
                  .build());
      unit.setStatus("reserved");
      unit.setOrderId("83663");
      tcgInventoryTable.putItem(unit);
    }

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
                        new FetchTcgClient.OfferListing(1001, "raw-nm", new BigDecimal("2.00")),
                        2,
                        new BigDecimal("1.50"))))));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order).isNotNull();
    assertThat(order.getStatus()).isEqualTo("awaiting_payment");
    var orderLines = OrderLines.parse(order.getLines(), objectMapper);
    assertThat(orderLines).hasSize(1);
    assertThat(orderLines.get(0).quantity()).isEqualTo(2);
    assertThat(orderLines.get(0).allocatedSequenceNumbers()).containsExactly(1, 2);

    var units = getUnits("jordan", "scryfall-1#normal#NM");
    var reserved = units.stream().filter(u -> "reserved".equals(u.getStatus())).toList();
    assertThat(reserved).hasSize(2);
    assertThat(reserved).allSatisfy(u -> assertThat(u.getOrderId()).isEqualTo("83663"));
    var inStock = units.stream().filter(u -> "in_stock".equals(u.getStatus())).toList();
    assertThat(inStock).hasSize(1);
    assertThat(inStock.get(0).getSequenceNumber()).isEqualTo(3);
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
    assertThat(upsert.frontImage()).isNull();
    assertThat(upsert.additionalImages()).isEmpty();
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
    assertThat(upsert.frontImage()).isNull();
    assertThat(upsert.additionalImages()).isEmpty();
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

  @Test
  void publishShouldStoreActionableErrorWhenAuthFails() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    fakeFetchTcgClient.seedSellerOffersFailure(
        new FetchTcgAuthException(401, "FetchTCG authentication failed with status 401"));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var job = getJob("jordan", "job1");
    assertThat(job.getStatus()).isEqualTo("failed");
    assertThat(job.getError())
        .isEqualTo("FetchTCG authentication failed. Replace the refresh token in settings.");
  }

  @Test
  void publishShouldStoreTruncatedRootCauseErrorWhenMessageIsLong() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    var message = "FetchTCG request failed with status 500 after 3 attempt(s): " + "x".repeat(5000);
    fakeFetchTcgClient.seedSellerOffersFailure(new RuntimeException(new IOException(message)));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var job = getJob("jordan", "job1");
    assertThat(job.getStatus()).isEqualTo("failed");
    assertThat(job.getError()).startsWith("FetchTCG request failed with status 500");
    assertThat(job.getError()).hasSize(301).endsWith("…");
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

  private void createDirtySkuWithUnit(String user, String skuId, int sequenceNumber) {
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
    tcgInventoryTable.putItem(skuItem);

    var unit =
        TcgInventoryItem.createUnit(
            user, skuId, sequenceNumber, "in_stock", "import1", Instant.ofEpochSecond(1700000000));
    tcgInventoryTable.putItem(unit);
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

  private void createSkuWithUnitAtSequence(
      String user, String skuId, int fetchtcgListingId, int sequenceNumber) {
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

    var unit =
        TcgInventoryItem.createUnit(
            user, skuId, sequenceNumber, "in_stock", "import1", Instant.ofEpochSecond(1700000000));
    tcgInventoryTable.putItem(unit);
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
            Map.of("NZ", new FetchTcgClient.PricingData(new BigDecimal("1.50"))),
            new FetchTcgClient.ExternalReferences("scryfall-1")));
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
