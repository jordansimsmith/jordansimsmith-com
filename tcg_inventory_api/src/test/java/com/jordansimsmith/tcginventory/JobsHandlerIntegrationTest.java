package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.queue.FakeQueueClient;
import com.jordansimsmith.tcginventory.imports.AppraiseJobProcessor;
import com.jordansimsmith.tcginventory.imports.ImportItem;
import com.jordansimsmith.tcginventory.imports.ImportRowItem;
import com.jordansimsmith.tcginventory.inventory.SkuItem;
import com.jordansimsmith.tcginventory.inventory.UnitItem;
import com.jordansimsmith.tcginventory.orders.OrderItem;
import com.jordansimsmith.tcginventory.orders.OrderLines;
import com.jordansimsmith.tcginventory.publish.ListingPhaseProcessor;
import com.jordansimsmith.tcginventory.settings.SettingsItem;
import com.jordansimsmith.time.FakeClock;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
  private DynamoDbTable<ImportItem> importTable;
  private DynamoDbTable<ImportRowItem> importRowTable;
  private DynamoDbTable<JobItem> jobTable;
  private DynamoDbTable<SkuItem> skuTable;
  private DynamoDbTable<UnitItem> unitTable;
  private DynamoDbTable<SettingsItem> settingsTable;
  private DynamoDbTable<OrderItem> orderTable;
  private DynamoDbTable<AuditItem> auditTable;

  private JobsHandler jobsHandler;

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  private static final URI UNUSED_S3_ENDPOINT = URI.create("http://localhost:1");
  private static final String SCRYFALL_ID = "29ba5a2d-d787-4214-8cd7-7f2bcea938f8";

  @BeforeAll
  static void setUpBeforeClass() {
    var factory =
        TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint(), UNUSED_S3_ENDPOINT);
    var table = factory.tableDefinition();
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
    importTable = factory.importTable();
    importRowTable = factory.importRowTable();
    jobTable = factory.jobTable();
    skuTable = factory.skuTable();
    unitTable = factory.unitTable();
    settingsTable = factory.settingsTable();
    orderTable = factory.orderTable();
    auditTable = factory.auditTable();

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
            Map.of("scryfallId", SCRYFALL_ID)));
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
            Map.of("scryfallId", SCRYFALL_ID)));

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
            Map.of("scryfallId", "scryfall-other")));
    fakeFetchTcgClient.seedCard(
        "mtg_410_c_dom_B_normal",
        new FetchTcgClient.GetCardResponse(
            "mtg_410_c_dom_B_normal",
            "Card 1 (Borderless)",
            Map.of("NZ", new FetchTcgClient.PricingData(new BigDecimal("27.43"))),
            Map.of("scryfallId", SCRYFALL_ID)));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    var row = getRow("jordan", "import1", 1);
    assertThat(row.getDecision()).isEqualTo("keep");
    assertThat(row.getFetchtcgCardId()).isEqualTo("mtg_410_c_dom_B_normal");
    assertThat(row.getMarketPrice()).isEqualTo("27.43");
  }

  @Test
  void appraiseShouldResolveWhenCardNameHasDiacritics() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var importItem =
        ImportItem.create(
            "jordan", "mtg", "import1", "test.csv", 1, null, Instant.ofEpochSecond(1700000000));
    importTable.putItem(importItem);
    importRowTable.putItem(
        ImportRowItem.create(
            "jordan",
            "import1",
            1,
            "Troll of Khazad-dûm",
            "ltr",
            "The Lord of the Rings: Tales of Middle-earth",
            "111",
            "normal",
            "NM",
            "scryfall",
            SCRYFALL_ID,
            "en"));
    createJob("jordan", "job1", "appraise", "queued", "import1");

    fakeFetchTcgClient.seedSearchResult(
        3268,
        "Troll of Khazad-dum",
        "normal",
        new FetchTcgClient.SearchCardsResponse(
            List.of(new FetchTcgClient.SearchCard("mtg_111_c_ltr_normal"))));
    fakeFetchTcgClient.seedCard(
        "mtg_111_c_ltr_normal",
        new FetchTcgClient.GetCardResponse(
            "mtg_111_c_ltr_normal",
            "Troll of Khazad-dum",
            Map.of("NZ", new FetchTcgClient.PricingData(new BigDecimal("1.50"))),
            Map.of("scryfallId", SCRYFALL_ID)));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    var row = getRow("jordan", "import1", 1);
    assertThat(row.getDecision()).isEqualTo("keep");
    assertThat(row.getFetchtcgCardId()).isEqualTo("mtg_111_c_ltr_normal");
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
            Map.of("scryfallId", "scryfall-other")));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    var row = getRow("jordan", "import1", 1);
    assertThat(row.getDecision()).isEqualTo("review");
    assertThat(row.getDecisionReason()).isEqualTo("unresolvable");
  }

  @Test
  void appraiseShouldRejectInvalidScryfallIdBeforeProviderLookup() {
    // arrange
    var importItem =
        ImportItem.create(
            "jordan", "mtg", "import1", "test.csv", 1, null, Instant.ofEpochSecond(1700000000));
    importTable.putItem(importItem);
    importRowTable.putItem(
        ImportRowItem.create(
            "jordan",
            "import1",
            1,
            "Forest",
            "lea",
            "Limited Edition Alpha",
            "1",
            "normal",
            "NM",
            "scryfall",
            "not/a-uuid",
            "en"));
    var jobItem = createJob("jordan", "job1", "appraise", "queued", "import1");

    // act & assert
    assertThatThrownBy(
            () ->
                new AppraiseJobProcessor(importTable, importRowTable, fakeClock, fakeFetchTcgClient)
                    .processBatch("jordan", jobItem))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("external id must contain only ASCII unreserved characters");
    assertThat(fakeFetchTcgClient.getSearchCallCount()).isZero();
    assertThat(getRow("jordan", "import1", 1).getDecision()).isNull();
  }

  @Test
  void appraiseShouldDedupeWithinBatch() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportWithRows(
        "jordan",
        "import1",
        List.of(
            new RowSpec("dom", "168", "normal", "NM", "en", SCRYFALL_ID),
            new RowSpec("dom", "168", "normal", "LP", "en", SCRYFALL_ID)));
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
            Map.of("scryfallId", SCRYFALL_ID)));

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
        JobItem.create("jordan", "job1", "appraise", "import1", Instant.ofEpochSecond(1700000000));
    jobItem.setStatus("succeeded");
    jobItem.setProcessedCount(5);
    jobItem.setContinuation(5);
    jobItem.setUpdatedAt(Instant.ofEpochSecond(1700000100));
    jobTable.putItem(jobItem);

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
      createDirtySkuWithUnit("jordan", "mtg#scryfall#scryfall-" + i + "#normal#NM", i);
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
    createSkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1001, 3);

    fakeFetchTcgClient.seedSellerOffers(
        List.of(
            new FetchTcgClient.SellerOffer(
                83663,
                "ACCEPTED",
                null,
                "2026-08-11T04:42:12.476+0000",
                "DELIVERY",
                "Chris Andrew (generic)",
                new FetchTcgClient.BuyerRegionAddress(
                    "32 Abercrombie Street", "", "Howick", "Auckland", "2014", "NZ"),
                new FetchTcgClient.ShippingOption("Economy Tracked"),
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
    assertThat(order.getDeliveryMode()).isEqualTo("DELIVERY");
    assertThat(order.getBuyerName()).isEqualTo("Chris Andrew (generic)");
    assertThat(order.getPostageOption()).isEqualTo("Economy Tracked");
    assertThat(order.getBuyerAddress())
        .isEqualTo(
            OrderItem.BuyerAddress.create(
                "32 Abercrombie Street", null, "Howick", "Auckland", "2014", "NZ"));
    assertThat(order.getTotalPrice()).isEqualTo("3.33");
    assertThat(order.getFetchtcgStatus()).isEqualTo("ACCEPTED");
    assertThat(order.getLines()).contains("mtg#scryfall#scryfall-1#normal#NM");
    var orderLines = OrderLines.parse(order.getLines(), objectMapper);
    assertThat(orderLines).hasSize(1);
    assertThat(orderLines.get(0).price()).isEqualTo("1.50");
    assertThat(orderLines.get(0).listedPrice()).isEqualTo("2.00");
    assertThat(orderLines.get(0).quantity()).isEqualTo(2);

    var sku = getSku("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    assertThat(sku.getDirty()).isFalse();
    assertThat(sku.getLastPublishedQuantity()).isEqualTo(1);

    var units = getUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM");
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
                null,
                null,
                null,
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
                null,
                null,
                null,
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
  void publishOrderPhaseShouldVoidOrderCancelledBySeller() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createSkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1001, 3);
    createReservedOrder(
        "jordan", "83663", "awaiting_payment", "mtg#scryfall#scryfall-1#normal#NM", 1001, 1, 2);

    fakeFetchTcgClient.seedSellerOffers(List.of(cancelledOffer(83663, "CANCELLED_BY_SELLER")));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    assertThat(getJob("jordan", "job1").getStatus()).isEqualTo("succeeded");

    var order = getOrder("jordan", "83663");
    assertThat(order.getStatus()).isEqualTo("voided");
    assertThat(order.getFetchtcgStatus()).isEqualTo("CANCELLED_BY_SELLER");
    assertThat(order.getFetchtcgCurrentAction()).isNull();

    var units = getUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    assertThat(units).allSatisfy(unit -> assertThat(unit.getStatus()).isEqualTo("in_stock"));
    assertThat(units).allSatisfy(unit -> assertThat(unit.getOrderId()).isNull());

    var releaseAudits =
        getAuditEntries("jordan").stream().filter(a -> "release".equals(a.getEventType())).toList();
    assertThat(releaseAudits).hasSize(1);
    assertThat(releaseAudits.get(0).getOrderId()).isEqualTo("83663");

    // the released units are dirty stock again, so the listing phase restores the full quantity
    var sku = getSku("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    assertThat(sku.getDirty()).isFalse();
    assertThat(sku.getLastPublishedQuantity()).isEqualTo(3);
  }

  @Test
  void publishOrderPhaseShouldVoidOrderCancelledByBuyer() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createSkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1001, 2);
    createReservedOrder(
        "jordan", "83663", "awaiting_payment", "mtg#scryfall#scryfall-1#normal#NM", 1001, 1);

    fakeFetchTcgClient.seedSellerOffers(List.of(cancelledOffer(83663, "CANCELLED_BY_BUYER")));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order.getStatus()).isEqualTo("voided");
    assertThat(order.getFetchtcgStatus()).isEqualTo("CANCELLED_BY_BUYER");

    var units = getUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    assertThat(units).allSatisfy(unit -> assertThat(unit.getStatus()).isEqualTo("in_stock"));
  }

  @Test
  void publishOrderPhaseShouldNotVoidOrderMissingFromSellerOffers() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createSkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1001, 2);
    createReservedOrder(
        "jordan", "83663", "awaiting_payment", "mtg#scryfall#scryfall-1#normal#NM", 1001, 1);

    fakeFetchTcgClient.seedSellerOffers(List.of());

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order.getStatus()).isEqualTo("awaiting_payment");

    var units = getUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    var reserved = units.stream().filter(u -> "reserved".equals(u.getStatus())).toList();
    assertThat(reserved).hasSize(1);
    assertThat(reserved.get(0).getOrderId()).isEqualTo("83663");

    assertThat(getAuditEntries("jordan")).noneMatch(a -> "release".equals(a.getEventType()));
  }

  @Test
  void publishOrderPhaseShouldNotVoidPaidOrder() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createSkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1001, 2);
    createReservedOrder("jordan", "83663", "to_pick", "mtg#scryfall#scryfall-1#normal#NM", 1001, 1);

    fakeFetchTcgClient.seedSellerOffers(List.of(cancelledOffer(83663, "CANCELLED_BY_SELLER")));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order.getStatus()).isEqualTo("to_pick");

    var units = getUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    var reserved = units.stream().filter(u -> "reserved".equals(u.getStatus())).toList();
    assertThat(reserved).hasSize(1);

    assertThat(getAuditEntries("jordan")).noneMatch(a -> "release".equals(a.getEventType()));
  }

  @Test
  void publishOrderPhaseShouldNotReleaseAgainWhenOrderAlreadyVoided() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createSkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1001, 2);
    createReservedOrder("jordan", "83663", "voided", "mtg#scryfall#scryfall-1#normal#NM", 1001, 1);
    releaseUnit("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1);

    fakeFetchTcgClient.seedSellerOffers(List.of(cancelledOffer(83663, "CANCELLED_BY_SELLER")));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order.getStatus()).isEqualTo("voided");

    var units = getUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    assertThat(units).allSatisfy(unit -> assertThat(unit.getStatus()).isEqualTo("in_stock"));

    assertThat(getAuditEntries("jordan")).noneMatch(a -> "release".equals(a.getEventType()));
  }

  @Test
  void publishOrderPhaseShouldFinishPartiallyAppliedRelease() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createSkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1001, 2);
    createReservedOrder(
        "jordan", "83663", "awaiting_payment", "mtg#scryfall#scryfall-1#normal#NM", 1001, 1, 2);

    // simulate a run that released one unit before dying, leaving the order awaiting_payment
    releaseUnit("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1);

    fakeFetchTcgClient.seedSellerOffers(List.of(cancelledOffer(83663, "CANCELLED_BY_SELLER")));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order.getStatus()).isEqualTo("voided");

    var units = getUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    assertThat(units).allSatisfy(unit -> assertThat(unit.getStatus()).isEqualTo("in_stock"));

    var releaseAudits =
        getAuditEntries("jordan").stream().filter(a -> "release".equals(a.getEventType())).toList();
    assertThat(releaseAudits).hasSize(1);
  }

  @Test
  void publishOrderPhaseShouldReleaseLargeOrderAcrossTransactions() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");

    var orderLines = new ArrayList<OrderLines.OrderLine>();
    for (int i = 1; i <= 60; i++) {
      var skuId = "mtg#scryfall#scryfall-" + i + "#normal#NM";
      createSkuWithUnits("jordan", skuId, 1000 + i, 1);
      reserveUnit("jordan", skuId, 1, "91329");
      orderLines.add(new OrderLines.OrderLine(skuId, 1000 + i, 1, "0.50", "0.50", List.of(1)));
    }
    createOrderWithLines("jordan", "91329", "awaiting_payment", orderLines);

    fakeFetchTcgClient.seedSellerOffers(List.of(cancelledOffer(91329, "CANCELLED_BY_SELLER")));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    assertThat(getJob("jordan", "job1").getStatus()).isEqualTo("succeeded");

    var order = getOrder("jordan", "91329");
    assertThat(order.getStatus()).isEqualTo("voided");

    for (int i = 1; i <= 60; i++) {
      var units = getUnits("jordan", "mtg#scryfall#scryfall-" + i + "#normal#NM");
      assertThat(units).hasSize(1);
      assertThat(units.get(0).getStatus()).isEqualTo("in_stock");
      assertThat(units.get(0).getOrderId()).isNull();
    }

    var releaseAudits =
        getAuditEntries("jordan").stream().filter(a -> "release".equals(a.getEventType())).toList();
    assertThat(releaseAudits).hasSize(1);
  }

  @Test
  void publishOrderPhaseShouldRefreshFulfillmentDetailsOnExistingOrders() {
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
                "Chris Andrew (generic)",
                new FetchTcgClient.BuyerRegionAddress(
                    "32 Abercrombie Street", null, "Howick", "Auckland", "2014", "NZ"),
                new FetchTcgClient.ShippingOption("Economy Tracked"),
                new BigDecimal("61.50"),
                List.of())));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var order = getOrder("jordan", "91329");
    assertThat(order.getBuyerName()).isEqualTo("Chris Andrew (generic)");
    assertThat(order.getPostageOption()).isEqualTo("Economy Tracked");
    assertThat(order.getBuyerAddress())
        .isEqualTo(
            OrderItem.BuyerAddress.create(
                "32 Abercrombie Street", null, "Howick", "Auckland", "2014", "NZ"));

    // the refresh moves no inventory or revenue, so it must not mark the report stale
    var auditEventTypes = getAuditEntries("jordan").stream().map(AuditItem::getEventType);
    assertThat(auditEventTypes).containsOnly("payment");
  }

  @Test
  void publishOrderPhaseShouldStoreNoAddressWhenEveryPartIsBlank() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createExistingOrder("jordan", "83663", "awaiting_payment");

    fakeFetchTcgClient.seedSellerOffers(
        List.of(
            new FetchTcgClient.SellerOffer(
                83663,
                "ACCEPTED",
                "AWAITING_PAYMENT",
                "2026-08-11T04:42:12.476+0000",
                "PICKUP",
                "Ben Creagh (sideswipe)",
                new FetchTcgClient.BuyerRegionAddress(null, "", null, null, null, null),
                null,
                new BigDecimal("3.33"),
                List.of())));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order.getBuyerName()).isEqualTo("Ben Creagh (sideswipe)");
    assertThat(order.getBuyerAddress()).isNull();
    assertThat(order.getPostageOption()).isNull();
  }

  @Test
  void publishOrderPhaseShouldNotRewriteUnchangedFulfillmentDetails() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createExistingOrder("jordan", "83663", "to_pick");
    fakeFetchTcgClient.seedSellerOffers(
        List.of(
            new FetchTcgClient.SellerOffer(
                83663,
                "ACCEPTED",
                "SEND_REVIEW",
                "2026-08-11T04:42:12.476+0000",
                "PICKUP",
                "Ben Creagh (sideswipe)",
                null,
                null,
                new BigDecimal("3.33"),
                List.of())));

    fakeClock.setTime(Instant.ofEpochSecond(1700005000));
    createPublishJob("jordan", "job1");
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);
    assertThat(getOrder("jordan", "83663").getUpdatedAt())
        .isEqualTo(Instant.ofEpochSecond(1700005000));

    // act
    fakeClock.setTime(Instant.ofEpochSecond(1700009999));
    createPublishJob("jordan", "job2");
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job2", "publish"), null);

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order.getBuyerName()).isEqualTo("Ben Creagh (sideswipe)");
    assertThat(order.getUpdatedAt()).isEqualTo(Instant.ofEpochSecond(1700005000));
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
                null,
                null,
                null,
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
                null,
                null,
                null,
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
    createSkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1001, 1);

    fakeFetchTcgClient.seedSellerOffers(
        List.of(
            new FetchTcgClient.SellerOffer(
                83663,
                "ACCEPTED",
                null,
                "2026-08-11T04:42:12.476+0000",
                "PICKUP",
                null,
                null,
                null,
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
    createSkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1001, 3);
    createTrackOrdersAfter("jordan", Instant.parse("2026-08-15T00:00:00Z"));

    fakeFetchTcgClient.seedSellerOffers(
        List.of(
            new FetchTcgClient.SellerOffer(
                83663,
                "ACCEPTED",
                null,
                "2026-08-11T04:42:12.476+0000",
                "PICKUP",
                null,
                null,
                null,
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

    var units = getUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    assertThat(units.stream().allMatch(u -> "in_stock".equals(u.getStatus()))).isTrue();
  }

  @Test
  void publishOrderPhaseShouldSkipOffersWithNullAcceptedAtWhenCutoffSet() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createSkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1001, 3);
    createTrackOrdersAfter("jordan", Instant.parse("2026-08-01T00:00:00Z"));

    fakeFetchTcgClient.seedSellerOffers(
        List.of(
            new FetchTcgClient.SellerOffer(
                83663,
                "ACCEPTED",
                null,
                null,
                "PICKUP",
                null,
                null,
                null,
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
    createSkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1001, 3);

    fakeFetchTcgClient.seedSellerOffers(
        List.of(
            new FetchTcgClient.SellerOffer(
                83663,
                "ACCEPTED",
                null,
                "2026-08-11T04:42:12.476+0000",
                "PICKUP",
                null,
                null,
                null,
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
    createSkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1001, 3);
    createTrackOrdersAfter("jordan", Instant.parse("2026-08-10T00:00:00Z"));

    fakeFetchTcgClient.seedSellerOffers(
        List.of(
            new FetchTcgClient.SellerOffer(
                83663,
                "ACCEPTED",
                null,
                "2026-08-11T04:42:12.476+0000",
                "PICKUP",
                null,
                null,
                null,
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
      createSkuWithUnitAtSequence(
          "jordan", "mtg#scryfall#scryfall-" + i + "#normal#NM", 2000 + i, i);
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
                null,
                null,
                null,
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
      var units = getUnits("jordan", "mtg#scryfall#scryfall-" + i + "#normal#NM");
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
    createSkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1001, 3);

    // simulate a run that crashed after reserving units but before writing the order
    var sku = getSku("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    sku.setDirty(true);
    sku.setGsi1pk(SkuItem.formatGsi1pk("jordan"));
    skuTable.putItem(sku);
    for (int sequenceNumber : new int[] {1, 2}) {
      var unit =
          unitTable.getItem(
              Key.builder()
                  .partitionValue(SkuItem.formatPk("jordan", "mtg#scryfall#scryfall-1#normal#NM"))
                  .sortValue(UnitItem.formatSk(sequenceNumber))
                  .build());
      unit.setStatus("reserved");
      unit.setOrderId("83663");
      unitTable.putItem(unit);
    }

    fakeFetchTcgClient.seedSellerOffers(
        List.of(
            new FetchTcgClient.SellerOffer(
                83663,
                "ACCEPTED",
                null,
                "2026-08-11T04:42:12.476+0000",
                "PICKUP",
                null,
                null,
                null,
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

    var units = getUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM");
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
    createDirtySkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 2, "1.50");

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var sku = getSku("jordan", "mtg#scryfall#scryfall-1#normal#NM");
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
    createDirtySkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 3, "2.00");
    var sku = getSku("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    sku.setFetchtcgListingId(975737);
    sku.setLastPublishedQuantity(1);
    sku.setLastPublishedPrice("1.80");
    skuTable.putItem(sku);

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var updated = getSku("jordan", "mtg#scryfall#scryfall-1#normal#NM");
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
    createDirtySkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 0, "1.50");
    var sku = getSku("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    sku.setFetchtcgListingId(975737);
    sku.setLastPublishedQuantity(1);
    sku.setLastPublishedPrice("1.50");
    skuTable.putItem(sku);

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var updated = getSku("jordan", "mtg#scryfall#scryfall-1#normal#NM");
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
    createDirtySkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 2, "1.50");

    // set dirty=false directly to simulate a race where the condition check
    // (dirty = true AND version = :captured) fails at write time
    var sku = getSku("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    sku.setDirty(false);
    skuTable.putItem(sku);

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert - the upsert was called (FetchTCG got the update)
    assertThat(fakeFetchTcgClient.getUpsertCalls()).hasSize(1);

    // but the listing snapshot was NOT written (condition failed)
    var updated = getSku("jordan", "mtg#scryfall#scryfall-1#normal#NM");
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
        SkuItem.create(
            user,
            skuId,
            parts[0],
            parts[1],
            parts[2],
            parts[3],
            parts[4],
            "Test Card",
            "dom",
            "Dominaria",
            "168",
            "mtg_168_c_dom_normal",
            suggestedPrice);
    skuTable.putItem(skuItem);

    for (int i = 1; i <= unitCount; i++) {
      var unit =
          UnitItem.create(
              user, "mtg", skuId, i, "in_stock", "import1", Instant.ofEpochSecond(1700000000));
      unitTable.putItem(unit);
    }
  }

  private void createPublishJob(String user, String jobId) {
    var jobItem = JobItem.create(user, jobId, "publish", null, Instant.ofEpochSecond(1700000000));
    jobTable.putItem(jobItem);
  }

  private void createDirtySkuWithUnit(String user, String skuId, int sequenceNumber) {
    var parts = skuId.split("#");
    var skuItem =
        SkuItem.create(
            user,
            skuId,
            parts[0],
            parts[1],
            parts[2],
            parts[3],
            parts[4],
            "Test Card",
            "dom",
            "Dominaria",
            "168",
            "mtg_168_c_dom_normal",
            "1.50");
    skuTable.putItem(skuItem);

    var unit =
        UnitItem.create(
            user,
            "mtg",
            skuId,
            sequenceNumber,
            "in_stock",
            "import1",
            Instant.ofEpochSecond(1700000000));
    unitTable.putItem(unit);
  }

  private void createSkuWithUnits(String user, String skuId, int fetchtcgListingId, int unitCount) {
    var parts = skuId.split("#");
    var skuItem =
        SkuItem.create(
            user,
            skuId,
            parts[0],
            parts[1],
            parts[2],
            parts[3],
            parts[4],
            "Test Card",
            "dom",
            "Dominaria",
            "168",
            "mtg_168_c_dom_normal",
            "1.50");
    skuItem.setDirty(false);
    skuItem.setGsi1pk(SkuItem.USER_PREFIX + user + "#CLEAN");
    skuItem.setFetchtcgListingId(fetchtcgListingId);
    skuTable.putItem(skuItem);

    for (int i = 1; i <= unitCount; i++) {
      var unit =
          UnitItem.create(
              user, "mtg", skuId, i, "in_stock", "import1", Instant.ofEpochSecond(1700000000));
      unitTable.putItem(unit);
    }
  }

  private void createSkuWithUnitAtSequence(
      String user, String skuId, int fetchtcgListingId, int sequenceNumber) {
    var parts = skuId.split("#");
    var skuItem =
        SkuItem.create(
            user,
            skuId,
            parts[0],
            parts[1],
            parts[2],
            parts[3],
            parts[4],
            "Test Card",
            "dom",
            "Dominaria",
            "168",
            "mtg_168_c_dom_normal",
            "1.50");
    skuItem.setDirty(false);
    skuItem.setGsi1pk(SkuItem.USER_PREFIX + user + "#CLEAN");
    skuItem.setFetchtcgListingId(fetchtcgListingId);
    skuTable.putItem(skuItem);

    var unit =
        UnitItem.create(
            user,
            "mtg",
            skuId,
            sequenceNumber,
            "in_stock",
            "import1",
            Instant.ofEpochSecond(1700000000));
    unitTable.putItem(unit);
  }

  private void createTrackOrdersAfter(String user, Instant trackOrdersAfter) {
    var settingsItem = new SettingsItem();
    settingsItem.setPk(SkuItem.formatUserPk(user));
    settingsItem.setSk(SettingsItem.formatSk());
    settingsItem.setTrackOrdersAfter(trackOrdersAfter);
    settingsTable.putItem(settingsItem);
  }

  private void createExistingOrder(String user, String offerId, String status) {
    var order =
        OrderItem.create(
            user,
            offerId,
            status,
            "ACCEPTED",
            null,
            "PICKUP",
            null,
            null,
            null,
            "3.33",
            "[]",
            Instant.ofEpochSecond(1700000000));
    orderTable.putItem(order);
  }

  private void createReservedOrder(
      String user,
      String offerId,
      String status,
      String skuId,
      int fetchtcgListingId,
      int... sequenceNumbers) {
    var allocated = Arrays.stream(sequenceNumbers).boxed().toList();
    for (var sequenceNumber : allocated) {
      reserveUnit(user, skuId, sequenceNumber, offerId);
    }

    createOrderWithLines(
        user,
        offerId,
        status,
        List.of(
            new OrderLines.OrderLine(
                skuId, fetchtcgListingId, allocated.size(), "3.33", "4.20", allocated)));
  }

  private void createOrderWithLines(
      String user, String offerId, String status, List<OrderLines.OrderLine> lines) {
    String linesJson;
    try {
      linesJson = objectMapper.writeValueAsString(lines);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    var order =
        OrderItem.create(
            user,
            offerId,
            status,
            "ACCEPTED",
            "AWAITING_PAYMENT",
            "PICKUP",
            null,
            null,
            null,
            "3.33",
            linesJson,
            Instant.ofEpochSecond(1700000000));
    orderTable.putItem(order);
  }

  private void reserveUnit(String user, String skuId, int sequenceNumber, String orderId) {
    var unit = getUnit(user, skuId, sequenceNumber);
    unit.setStatus("reserved");
    unit.setOrderId(orderId);
    unitTable.putItem(unit);
  }

  private void releaseUnit(String user, String skuId, int sequenceNumber) {
    var unit = getUnit(user, skuId, sequenceNumber);
    unit.setStatus("in_stock");
    unit.setOrderId(null);
    unitTable.putItem(unit);
  }

  private UnitItem getUnit(String user, String skuId, int sequenceNumber) {
    return unitTable.getItem(
        Key.builder()
            .partitionValue(SkuItem.formatPk(user, skuId))
            .sortValue(UnitItem.formatSk(sequenceNumber))
            .build());
  }

  private static FetchTcgClient.SellerOffer cancelledOffer(int offerId, String status) {
    return new FetchTcgClient.SellerOffer(
        offerId,
        status,
        null,
        "2026-08-11T04:42:12.476+0000",
        "PICKUP",
        null,
        null,
        null,
        new BigDecimal("3.33"),
        List.of());
  }

  private OrderItem getOrder(String user, String offerId) {
    return orderTable.getItem(
        Key.builder()
            .partitionValue(SkuItem.formatUserPk(user))
            .sortValue(OrderItem.formatSk(offerId))
            .build());
  }

  private SkuItem getSku(String user, String skuId) {
    return skuTable.getItem(
        Key.builder()
            .partitionValue(SkuItem.formatPk(user, skuId))
            .sortValue(SkuItem.formatSk())
            .build());
  }

  private List<UnitItem> getUnits(String user, String skuId) {
    var results = new ArrayList<UnitItem>();
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(SkuItem.formatPk(user, skuId))
                        .sortValue(UnitItem.UNIT_PREFIX)
                        .build()))
            .build();
    unitTable.query(request).items().forEach(results::add);
    return results;
  }

  private List<AuditItem> getAuditEntries(String user) {
    var results = new ArrayList<AuditItem>();
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.keyEqualTo(
                    Key.builder().partitionValue(AuditItem.formatPk(user)).build()))
            .build();
    auditTable.query(request).items().forEach(results::add);
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
            Map.of("scryfallId", SCRYFALL_ID)));
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
        List.of(new RowSpec(setCode, collectorNumber, finish, condition, language, SCRYFALL_ID)));
  }

  private void createImportWithRows(String user, String importId, List<RowSpec> rows) {
    var importItem =
        ImportItem.create(
            user,
            "mtg",
            importId,
            "test.csv",
            rows.size(),
            null,
            Instant.ofEpochSecond(1700000000));
    importTable.putItem(importItem);

    for (int i = 0; i < rows.size(); i++) {
      var spec = rows.get(i);
      var rowItem =
          ImportRowItem.create(
              user,
              importId,
              i + 1,
              "Card " + (i + 1),
              spec.setCode(),
              "Test Set",
              spec.collectorNumber(),
              spec.finish(),
              spec.condition(),
              "scryfall",
              spec.scryfallId(),
              spec.language());
      importRowTable.putItem(rowItem);
    }
  }

  private void createImportWithNRows(String user, String importId, int rowCount) {
    var importItem =
        ImportItem.create(
            user, "mtg", importId, "test.csv", rowCount, null, Instant.ofEpochSecond(1700000000));
    importTable.putItem(importItem);

    for (int i = 1; i <= rowCount; i++) {
      var rowItem =
          ImportRowItem.create(
              user,
              importId,
              i,
              "Card " + i,
              "dom",
              "Dominaria",
              "168",
              "normal",
              "NM",
              "scryfall",
              SCRYFALL_ID,
              "en");
      importRowTable.putItem(rowItem);
    }
  }

  private JobItem createJob(
      String user, String jobId, String jobType, String status, String importId) {
    var jobItem = JobItem.create(user, jobId, jobType, importId, Instant.ofEpochSecond(1700000000));
    if (!"queued".equals(status)) {
      jobItem.setStatus(status);
    }
    jobTable.putItem(jobItem);
    return jobItem;
  }

  private ImportRowItem getRow(String user, String importId, int position) {
    return importRowTable.getItem(
        Key.builder()
            .partitionValue(ImportRowItem.formatPk(user, importId))
            .sortValue(ImportRowItem.formatSk(position))
            .build());
  }

  private ImportItem getImport(String user, String importId) {
    return importTable.getItem(
        Key.builder()
            .partitionValue(SkuItem.formatUserPk(user))
            .sortValue(ImportItem.formatSk(importId))
            .build());
  }

  private JobItem getJob(String user, String jobId) {
    return jobTable.getItem(
        Key.builder()
            .partitionValue(SkuItem.formatUserPk(user))
            .sortValue(JobItem.formatSk(jobId))
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
