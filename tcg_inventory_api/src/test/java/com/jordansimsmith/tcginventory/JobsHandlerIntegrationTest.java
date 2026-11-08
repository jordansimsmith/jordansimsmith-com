package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.fetchtcg.client.BuyerAddress;
import com.jordansimsmith.tcginventory.fetchtcg.client.CardListing;
import com.jordansimsmith.tcginventory.fetchtcg.client.CardSearchResult;
import com.jordansimsmith.tcginventory.fetchtcg.client.CardSet;
import com.jordansimsmith.tcginventory.fetchtcg.client.FakeFetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetCardResponse;
import com.jordansimsmith.tcginventory.fetchtcg.client.Offer;
import com.jordansimsmith.tcginventory.fetchtcg.client.OfferItem;
import com.jordansimsmith.tcginventory.fetchtcg.client.OfferListing;
import com.jordansimsmith.tcginventory.fetchtcg.client.ShippingOption;
import com.jordansimsmith.tcginventory.imports.AppraiseJobProcessor;
import com.jordansimsmith.tcginventory.imports.ImportItem;
import com.jordansimsmith.tcginventory.imports.ImportRowItem;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import com.jordansimsmith.tcginventory.inventory.client.FindSkusRequest;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsRequest;
import com.jordansimsmith.tcginventory.inventory.client.GetSkuRequest;
import com.jordansimsmith.tcginventory.inventory.client.ReleaseUnitRequest;
import com.jordansimsmith.tcginventory.inventory.client.Sku;
import com.jordansimsmith.tcginventory.inventory.client.Unit;
import com.jordansimsmith.tcginventory.inventory.client.UnitStatus;
import com.jordansimsmith.tcginventory.listings.client.FindListingsRequest;
import com.jordansimsmith.tcginventory.listings.client.Listing;
import com.jordansimsmith.tcginventory.listings.client.UpdateListingRequest;
import com.jordansimsmith.tcginventory.orders.OrderItem;
import com.jordansimsmith.tcginventory.orders.OrderRowItem;
import com.jordansimsmith.tcginventory.pricing.client.GetPriceRequest;
import com.jordansimsmith.tcginventory.pricing.client.GetPriceResponse;
import com.jordansimsmith.tcginventory.pricing.client.GetPricingRequest;
import com.jordansimsmith.tcginventory.pricing.client.PricingService;
import com.jordansimsmith.tcginventory.pricing.client.UpdatePricingRequest;
import com.jordansimsmith.tcginventory.publish.ListingPhaseProcessor;
import com.jordansimsmith.time.FakeClock;
import java.math.BigDecimal;
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
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

@Testcontainers
public class JobsHandlerIntegrationTest {

  private FakeClock fakeClock;
  private FakeQueueClient<JobMessage> fakeJobsQueue;
  private FakeFetchTcgService fakeFetchTcgService;
  private ObjectMapper objectMapper;
  private DynamoDbTable<ImportItem> importTable;
  private DynamoDbTable<ImportRowItem> importRowTable;
  private DynamoDbTable<JobItem> jobTable;
  private DynamoDbTable<OrderItem> orderTable;
  private TcgInventoryTestFactory factory;

  private JobsHandler jobsHandler;

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  private static final String SCRYFALL_ID = "29ba5a2d-d787-4214-8cd7-7f2bcea938f8";

  @BeforeAll
  static void setUpBeforeClass() {
    var factory =
        TcgInventoryTestFactory.create(
            dynamoDbContainer.getEndpoint(), TcgInventoryTestFactory.UNUSED_S3_ENDPOINT);
    var table = factory.tableDefinition();
    DynamoDbUtils.createTable(factory.dynamoDbClient(), table);
  }

  @BeforeEach
  void setUp() {
    factory =
        TcgInventoryTestFactory.create(
            dynamoDbContainer.getEndpoint(), TcgInventoryTestFactory.UNUSED_S3_ENDPOINT);

    fakeClock = factory.fakeClock();
    fakeJobsQueue = factory.fakeJobsQueue();
    fakeFetchTcgService = factory.fakeFetchTcgService();
    objectMapper = factory.objectMapper();
    importTable = factory.importTable();
    importRowTable = factory.importRowTable();
    jobTable = factory.jobTable();
    orderTable = factory.orderTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());
    fakeJobsQueue.reset();
    fakeFetchTcgService.reset();

    jobsHandler = new JobsHandler(factory);
  }

  @Test
  void appraiseShouldResolveIdentityAndKeep() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportWithRow("jordan", "import1", "dom", "168", "normal", "NM", "en");
    createJob("jordan", "job1", "appraise", "queued", "import1");

    fakeFetchTcgService.seedSearchResult(
        "mtg", 2624, "Card 1", "normal", List.of(new CardSearchResult("mtg_168_c_dom_normal")));
    seedCard(
        "mtg_168_c_dom_normal",
        new GetCardResponse(
            "mtg_168_c_dom_normal",
            "Card 1",
            new BigDecimal("0.30"),
            Map.of("scryfallId", SCRYFALL_ID)));
    factory
        .fakeFetchTcgService()
        .seedListings(
            "mtg_168_c_dom_normal",
            List.of(new CardListing(1, "raw-nm", new BigDecimal("0.25"), "rival1")));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    var row = getRow("jordan", "import1", 1);
    assertThat(row.getDecision()).isEqualTo("keep");
    assertThat(row.getMarketPrice()).isEqualTo("0.30");
    assertThat(row.getSuggestedPrice()).isEqualTo("0.25");

    var importItem = getImport("jordan", "import1");
    assertThat(importItem.getStatus()).isEqualTo("review");
  }

  @Test
  void appraiseShouldExcludeTheFetchTcgUsersOwnListingFromCompetitorPrices() throws Exception {
    createImportWithRow("jordan", "import1", "dom", "168", "normal", "NM", "en");
    var jobItem = createJob("jordan", "job1", "appraise", "queued", "import1");
    factory
        .pricingService()
        .updatePricing(new RequestContext("jordan"), new UpdatePricingRequest(new BigDecimal("0")));
    seedDefaultCardForDom168();
    seedCard(
        "mtg_168_c_dom_normal",
        new GetCardResponse(
            "mtg_168_c_dom_normal",
            "Card 1",
            new BigDecimal("1.00"),
            Map.of("scryfallId", SCRYFALL_ID)));
    factory
        .fakeFetchTcgService()
        .seedListings(
            "mtg_168_c_dom_normal",
            List.of(
                new CardListing(1, "raw-nm", new BigDecimal("0.40"), "jordan"),
                new CardListing(2, "raw-nm", new BigDecimal("0.95"), "rival1")));

    newAppraiseJobProcessor().processBatch("jordan", jobItem);

    assertThat(getRow("jordan", "import1", 1).getSuggestedPrice()).isEqualTo("0.90");
  }

  @Test
  void appraiseShouldResolveSellerIdentityIndependentlyForEachUser() {
    createImportWithRow("jordan", "import1", "dom", "168", "normal", "NM", "en");
    var jordanJob = createJob("jordan", "jordan-job", "appraise", "queued", "import1");
    createImportWithRow("other-user", "import2", "dom", "168", "normal", "NM", "en");
    var otherUserJob = createJob("other-user", "other-user-job", "appraise", "queued", "import2");
    fakeFetchTcgService.seedSearchResult(
        "mtg", 2624, "Card 1", "normal", List.of(new CardSearchResult("mtg_168_c_dom_normal")));
    seedCard(
        "mtg_168_c_dom_normal",
        new GetCardResponse(
            "mtg_168_c_dom_normal",
            "Card 1",
            new BigDecimal("1.00"),
            Map.of("scryfallId", SCRYFALL_ID)));
    factory
        .fakeFetchTcgService()
        .seedListings(
            "mtg_168_c_dom_normal",
            List.of(
                new CardListing(1, "raw-nm", new BigDecimal("0.40"), "jordan"),
                new CardListing(2, "raw-nm", new BigDecimal("0.95"), "other-user")));

    factory.fakeFetchTcgService().seedSellerUsername("jordan", "jordan");
    newAppraiseJobProcessor().processBatch("jordan", jordanJob);
    factory.fakeFetchTcgService().seedSellerUsername("other-user", "other-user");
    newAppraiseJobProcessor().processBatch("other-user", otherUserJob);

    assertThat(getRow("jordan", "import1", 1).getSuggestedPrice()).isEqualTo("0.90");
    assertThat(getRow("other-user", "import2", 1).getSuggestedPrice()).isEqualTo("1.00");
  }

  @Test
  void appraiseShouldPreserveFetchTcgAccountNotFoundMessage() throws Exception {
    createImportWithRow("jordan", "import1", "dom", "168", "normal", "NM", "en");
    var jobItem = createJob("jordan", "job1", "appraise", "queued", "import1");
    seedDefaultCardForDom168();
    var pricingService = mock(PricingService.class);
    when(pricingService.getPrice(
            new RequestContext("jordan"),
            new GetPriceRequest("mtg_168_c_dom_normal", Condition.NM)))
        .thenThrow(new NotFoundException("missing"));

    var result =
        newAppraiseJobProcessor(factory.fakeFetchTcgService(), pricingService)
            .processBatch("jordan", jobItem);

    assertThat(result).isEqualTo(new JobProcessor.FailureJobResult("missing"));
    assertThat(getRow("jordan", "import1", 1).getDecision()).isNull();
    assertThat(getImport("jordan", "import1").getError()).isEqualTo("missing");
  }

  @Test
  void appraiseShouldUseServiceMarketPriceAfterCatalogResolution() throws Exception {
    // arrange
    createImportWithRow("jordan", "import1", "dom", "168", "normal", "NM", "en");
    var jobItem = createJob("jordan", "job1", "appraise", "queued", "import1");
    fakeFetchTcgService.seedSearchResult(
        "mtg", 2624, "Card 1", "normal", List.of(new CardSearchResult("mtg_168_c_dom_normal")));
    seedCard(
        "mtg_168_c_dom_normal",
        new GetCardResponse(
            "mtg_168_c_dom_normal",
            "Card 1",
            new BigDecimal("0.30"),
            Map.of("scryfallId", SCRYFALL_ID)));
    var fetchTcgService = factory.fakeFetchTcgService();
    var ctx = new RequestContext("jordan");
    var pricingService = mock(PricingService.class);
    when(pricingService.getPrice(ctx, new GetPriceRequest("mtg_168_c_dom_normal", Condition.NM)))
        .thenReturn(new GetPriceResponse(new BigDecimal("1.20"), new BigDecimal("1.20")));

    // act
    newAppraiseJobProcessor(fetchTcgService, pricingService).processBatch("jordan", jobItem);

    // assert
    verify(pricingService).getPrice(ctx, new GetPriceRequest("mtg_168_c_dom_normal", Condition.NM));
    var row = getRow("jordan", "import1", 1);
    assertThat(row.getMarketPrice()).isEqualTo("1.20");
    assertThat(row.getSuggestedPrice()).isEqualTo("1.20");
  }

  @Test
  void appraiseShouldRequestFreshPricingForRepeatedResolvedRows() throws Exception {
    // arrange
    createImportWithNRows("jordan", "import1", 2);
    var jobItem = createJob("jordan", "job1", "appraise", "queued", "import1");
    fakeFetchTcgService.seedSearchResult(
        "mtg", 2624, "Card 1", "normal", List.of(new CardSearchResult("shared-card")));
    fakeFetchTcgService.seedSearchResult(
        "mtg", 2624, "Card 2", "normal", List.of(new CardSearchResult("shared-card")));
    seedCard(
        "shared-card",
        new GetCardResponse(
            "shared-card", "Card", BigDecimal.ONE, Map.of("scryfallId", SCRYFALL_ID)));
    var pricingService = mock(PricingService.class);
    var ctx = new RequestContext("jordan");
    var request = new GetPriceRequest("shared-card", Condition.NM);
    when(pricingService.getPrice(ctx, request))
        .thenReturn(new GetPriceResponse(new BigDecimal("1.00"), new BigDecimal("0.95")));

    // act
    newAppraiseJobProcessor(fakeFetchTcgService, pricingService).processBatch("jordan", jobItem);

    // assert
    verify(pricingService, times(2)).getPrice(ctx, request);
    assertThat(getRow("jordan", "import1", 1).getSuggestedPrice()).isEqualTo("0.95");
    assertThat(getRow("jordan", "import1", 2).getSuggestedPrice()).isEqualTo("0.95");
  }

  @Test
  void appraiseShouldUseDefaultFloorWhenStoredPricingItemHasNoFloor() throws Exception {
    // arrange
    createImportWithRow("jordan", "import1", "dom", "168", "normal", "NM", "en");
    var jobItem = createJob("jordan", "job1", "appraise", "queued", "import1");
    factory
        .dynamoDbClient()
        .putItem(
            PutItemRequest.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .item(
                    Map.of(
                        "pk", AttributeValue.fromS("USER#jordan"),
                        "sk", AttributeValue.fromS("PRICING"),
                        "user", AttributeValue.fromS("jordan")))
                .build());
    fakeFetchTcgService.seedSearchResult(
        "mtg", 2624, "Card 1", "normal", List.of(new CardSearchResult("mtg_168_c_dom_normal")));
    seedCard(
        "mtg_168_c_dom_normal",
        new GetCardResponse(
            "mtg_168_c_dom_normal",
            "Card 1",
            new BigDecimal("0.30"),
            Map.of("scryfallId", SCRYFALL_ID)));
    factory
        .fakeFetchTcgService()
        .seedListings(
            "mtg_168_c_dom_normal",
            List.of(new CardListing(1, "raw-nm", new BigDecimal("0.25"), "rival1")));

    // act
    newAppraiseJobProcessor().processBatch("jordan", jobItem);

    // assert
    assertThat(getRow("jordan", "import1", 1).getSuggestedPrice()).isEqualTo("0.25");
    assertThat(
            factory
                .pricingService()
                .getPricing(new RequestContext("jordan"), new GetPricingRequest())
                .priceFloor())
        .isEqualTo("0.25");
  }

  @Test
  void appraiseShouldApplyConfiguredFloorAcrossContinuationAndPreserveDecidedRows()
      throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    int totalRows = AppraiseJobProcessor.BATCH_SIZE + 2;
    createImportWithNRows("jordan", "import1", totalRows);
    var alreadyDecidedRow = getRow("jordan", "import1", 1);
    alreadyDecidedRow.setDecision("keep");
    alreadyDecidedRow.setDecisionReason("already decided");
    alreadyDecidedRow.setMarketPrice("9.99");
    alreadyDecidedRow.setSuggestedPrice("8.88");
    alreadyDecidedRow.setFetchtcgCardId("existing-card");
    alreadyDecidedRow.setFetchtcgSetId(42);
    importRowTable.putItem(alreadyDecidedRow);
    factory
        .pricingService()
        .updatePricing(
            new RequestContext("jordan"), new UpdatePricingRequest(new BigDecimal("2.00")));
    createJob("jordan", "job1", "appraise", "queued", "import1");

    seedDefaultCardForDom168();

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    var firstBatchRow = getRow("jordan", "import1", AppraiseJobProcessor.BATCH_SIZE);
    assertThat(firstBatchRow.getDecision()).isEqualTo("keep");
    assertThat(firstBatchRow.getSuggestedPrice()).isEqualTo("2.00");
    assertThat(getRow("jordan", "import1", AppraiseJobProcessor.BATCH_SIZE + 1).getDecision())
        .isNull();
    var unchangedRow = getRow("jordan", "import1", 1);
    assertThat(unchangedRow.getDecision()).isEqualTo("keep");
    assertThat(unchangedRow.getDecisionReason()).isEqualTo("already decided");
    assertThat(unchangedRow.getMarketPrice()).isEqualTo("9.99");
    assertThat(unchangedRow.getSuggestedPrice()).isEqualTo("8.88");
    assertThat(unchangedRow.getFetchtcgCardId()).isEqualTo("existing-card");
    assertThat(unchangedRow.getFetchtcgSetId()).isEqualTo(42);

    // act - continuation batch
    fakeJobsQueue.reset();
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    var continuationRow = getRow("jordan", "import1", AppraiseJobProcessor.BATCH_SIZE + 1);
    assertThat(continuationRow.getDecision()).isEqualTo("keep");
    assertThat(continuationRow.getSuggestedPrice()).isEqualTo("2.00");
    assertThat(getRow("jordan", "import1", totalRows).getSuggestedPrice()).isEqualTo("2.00");
    assertThat(getJob("jordan", "job1").getStatus()).isEqualTo("succeeded");
  }

  @Test
  void appraiseShouldSearchEtchedFinishAsFoilAndKeepStoredFinish() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportWithRow("jordan", "import1", "sta", "13", "etched", "NM", "en");
    createJob("jordan", "job1", "appraise", "queued", "import1");
    fakeFetchTcgService.seedSearchResult(
        "mtg", 3243, "Card 1", "foil", List.of(new CardSearchResult("etched-card")));
    seedCard(
        "etched-card",
        new GetCardResponse(
            "etched-card",
            "Card 1 (Foil Etched)",
            new BigDecimal("1.50"),
            Map.of("scryfallId", SCRYFALL_ID)));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    var row = getRow("jordan", "import1", 1);
    assertThat(row.getDecision()).isEqualTo("keep");
    assertThat(row.getFinish()).isEqualTo("etched");
    assertThat(row.getFetchtcgCardId()).isEqualTo("etched-card");
  }

  @Test
  void appraiseShouldKeepFoilSearchFinishUnchanged() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportWithRow("jordan", "import1", "dom", "168", "foil", "NM", "en");
    createJob("jordan", "job1", "appraise", "queued", "import1");
    fakeFetchTcgService.seedSearchResult(
        "mtg", 2624, "Card 1", "foil", List.of(new CardSearchResult("foil-card")));
    seedCard(
        "foil-card",
        new GetCardResponse(
            "foil-card", "Card 1", new BigDecimal("1.50"), Map.of("scryfallId", SCRYFALL_ID)));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    var row = getRow("jordan", "import1", 1);
    assertThat(row.getDecision()).isEqualTo("keep");
    assertThat(row.getFinish()).isEqualTo("foil");
    assertThat(row.getFetchtcgCardId()).isEqualTo("foil-card");
  }

  @Test
  void appraiseShouldRejectUnexpectedFinish() {
    // arrange
    createImportWithRow("jordan", "import1", "dom", "168", "foil-etched", "NM", "en");
    var jobItem = createJob("jordan", "job1", "appraise", "queued", "import1");

    // act/assert
    assertThatThrownBy(() -> newAppraiseJobProcessor().processBatch("jordan", jobItem))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("unsupported finish for game: foil-etched");
  }

  @Test
  void appraiseShouldReviewAmbiguousEtchedCardsWithoutFetchingListings() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportWithRow("jordan", "import1", "sta", "13", "etched", "NM", "en");
    createJob("jordan", "job1", "appraise", "queued", "import1");
    fakeFetchTcgService.seedSearchResult(
        "mtg",
        3243,
        "Card 1",
        "foil",
        List.of(new CardSearchResult("regular-foil-card"), new CardSearchResult("etched-card")));
    seedCard(
        "regular-foil-card",
        new GetCardResponse(
            "regular-foil-card",
            "Card 1",
            new BigDecimal("1.50"),
            Map.of("scryfallId", SCRYFALL_ID)));
    seedCard(
        "etched-card",
        new GetCardResponse(
            "etched-card",
            "Card 1 (Foil Etched)",
            new BigDecimal("2.50"),
            Map.of("scryfallId", SCRYFALL_ID)));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    var row = getRow("jordan", "import1", 1);
    assertThat(row.getDecision()).isEqualTo("review");
    assertThat(row.getDecisionReason()).isEqualTo("unresolvable");
    assertThat(row.getFetchtcgCardId()).isNull();
    assertThat(row.getMarketPrice()).isNull();
    assertThat(row.getSuggestedPrice()).isNull();
  }

  @Test
  void appraiseShouldDiscardBelowThreshold() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportWithRow("jordan", "import1", "dom", "168", "normal", "NM", "en");
    createJob("jordan", "job1", "appraise", "queued", "import1");

    fakeFetchTcgService.seedSearchResult(
        "mtg", 2624, "Card 1", "normal", List.of(new CardSearchResult("mtg_168_c_dom_normal")));
    seedCard(
        "mtg_168_c_dom_normal",
        new GetCardResponse(
            "mtg_168_c_dom_normal",
            "Card 1",
            new BigDecimal("0.10"),
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
    var jobItem = createJob("jordan", "job1", "appraise", "queued", "import1");
    var pricingService = mock(PricingService.class);

    // act
    newAppraiseJobProcessor(factory.fakeFetchTcgService(), pricingService)
        .processBatch("jordan", jobItem);

    // assert
    verifyNoInteractions(pricingService);
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

    fakeFetchTcgService.seedSearchResult(
        "mtg",
        2624,
        "Card 1",
        "normal",
        List.of(
            new CardSearchResult("mtg_168_c_dom_normal"),
            new CardSearchResult("mtg_410_c_dom_B_normal")));
    seedCard(
        "mtg_168_c_dom_normal",
        new GetCardResponse(
            "mtg_168_c_dom_normal",
            "Card 1",
            new BigDecimal("1.50"),
            Map.of("scryfallId", "scryfall-other")));
    seedCard(
        "mtg_410_c_dom_B_normal",
        new GetCardResponse(
            "mtg_410_c_dom_B_normal",
            "Card 1 (Borderless)",
            new BigDecimal("27.43"),
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
            SCRYFALL_ID,
            "en"));
    createJob("jordan", "job1", "appraise", "queued", "import1");

    fakeFetchTcgService.seedSearchResult(
        "mtg",
        3268,
        "Troll of Khazad-dum",
        "normal",
        List.of(new CardSearchResult("mtg_111_c_ltr_normal")));
    fakeFetchTcgService.seedSets(
        "mtg", "ltr", List.of(new CardSet(3268, "The Lord of the Rings: Tales of Middle-earth")));
    seedCard(
        "mtg_111_c_ltr_normal",
        new GetCardResponse(
            "mtg_111_c_ltr_normal",
            "Troll of Khazad-dum",
            new BigDecimal("1.50"),
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

    fakeFetchTcgService.seedSearchResult(
        "mtg", 2624, "Card 1", "normal", List.of(new CardSearchResult("mtg_168_c_dom_normal")));
    seedCard(
        "mtg_168_c_dom_normal",
        new GetCardResponse(
            "mtg_168_c_dom_normal",
            "Card 1",
            new BigDecimal("1.50"),
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
            "not/a-uuid",
            "en"));
    var jobItem = createJob("jordan", "job1", "appraise", "queued", "import1");

    // act & assert
    assertThatThrownBy(() -> newAppraiseJobProcessor().processBatch("jordan", jobItem))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("external id must contain only ASCII unreserved characters");
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

    fakeFetchTcgService.seedSearchResult(
        "mtg", 2624, "Card 1", "normal", List.of(new CardSearchResult("mtg_168_c_dom_normal")));
    seedCard(
        "mtg_168_c_dom_normal",
        new GetCardResponse(
            "mtg_168_c_dom_normal",
            "Card 1",
            new BigDecimal("1.50"),
            Map.of("scryfallId", SCRYFALL_ID)));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert

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
    assertThat(jobItem.getContinuation())
        .isEqualTo(Integer.toString(AppraiseJobProcessor.BATCH_SIZE));
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
    assertThat(completedJob.getContinuation()).isNull();
    assertThat(fakeJobsQueue.getMessages()).isEmpty();

    var importItem = getImport("jordan", "import1");
    assertThat(importItem.getStatus()).isEqualTo("review");
  }

  @Test
  void duplicateDeliveryShouldNoOpWhenSucceeded() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));

    var jobItem =
        JobItem.create(
            "jordan", "job1", "appraise", "import1", null, Instant.ofEpochSecond(1700000000));
    jobItem.setStatus("succeeded");
    jobItem.setProcessedCount(5);
    jobItem.setContinuation("5");
    jobItem.setUpdatedAt(Instant.ofEpochSecond(1700000100));
    jobTable.putItem(jobItem);

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    var updatedJob = getJob("jordan", "job1");
    assertThat(updatedJob.getStatus()).isEqualTo("succeeded");
    assertThat(updatedJob.getContinuation()).isEqualTo("5");
    assertThat(updatedJob.getProcessedCount()).isEqualTo(5);
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
    assertThat(jobItem.getContinuation())
        .isEqualTo(Integer.toString(ListingPhaseProcessor.BATCH_SIZE));
    assertThat(fakeJobsQueue.getSends()).hasSize(1);
    var continuationSend = fakeJobsQueue.getSends().get(0);
    assertThat(continuationSend.messageGroupId()).isEqualTo("jordan");
    assertThat(continuationSend.messageDeduplicationId())
        .isEqualTo("job1#" + ListingPhaseProcessor.BATCH_SIZE);

    // act - second batch
    fakeJobsQueue.reset();
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var completedJob = getJob("jordan", "job1");
    assertThat(completedJob.getStatus()).isEqualTo("succeeded");
    assertThat(completedJob.getProcessedCount()).isEqualTo(totalSkus);
    assertThat(completedJob.getContinuation()).isNull();
    assertThat(fakeJobsQueue.getMessages()).isEmpty();
  }

  @Test
  void publishOrderPhaseShouldReserveUnitsForNewOffer() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createSkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1001, 3);

    factory
        .fakeFetchTcgService()
        .seedOffers(
            "jordan",
            List.of(
                new Offer(
                    83663,
                    "ACCEPTED",
                    null,
                    "2026-08-11T04:42:12.476+0000",
                    "DELIVERY",
                    "Chris Andrew (generic)",
                    new BuyerAddress(
                        "32 Abercrombie Street", "", "Howick", "Auckland", "2014", "NZ"),
                    new ShippingOption("Economy Tracked"),
                    new BigDecimal("3.33"),
                    List.of(
                        new OfferItem(
                            new OfferListing(1001, "raw-nm", new BigDecimal("2.00")),
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
    var orderLines = findOrderRows(order);
    assertThat(orderLines).hasSize(1);
    assertThat(orderLines.get(0).getSkuId()).isEqualTo("mtg#scryfall#scryfall-1#normal#NM");
    assertThat(orderLines.get(0).getPrice()).isEqualTo("1.50");
    assertThat(orderLines.get(0).getListedPrice()).isEqualTo("2.00");
    assertThat(orderLines.get(0).getQuantity()).isEqualTo(2);

    var sku = getSku("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    assertThat(isDirty("jordan", sku.skuId())).isFalse();

    var units = getUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    var reserved = units.stream().filter(u -> u.status() == UnitStatus.RESERVED).toList();
    assertThat(reserved).hasSize(2);
    assertThat(reserved.get(0).reservationId()).isEqualTo("83663");
  }

  @Test
  void publishOrderPhaseShouldAdvanceToPickOnPayment() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createExistingOrder("jordan", "83663", "awaiting_payment");

    factory
        .fakeFetchTcgService()
        .seedOffers(
            "jordan",
            List.of(
                new Offer(
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

    createPublishJob("jordan", "job2");
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job2", "publish"), null);

    var replayedOrder = getOrder("jordan", "83663");
    assertThat(replayedOrder.getStatus()).isEqualTo("to_pick");
    assertThat(replayedOrder.getFetchtcgCurrentAction()).isEqualTo("SEND_PICKUP_ADDRESS");
  }

  @Test
  void publishOrderPhaseShouldAdvanceToPickOnTrackingCode() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createExistingOrder("jordan", "91329", "awaiting_payment");

    factory
        .fakeFetchTcgService()
        .seedOffers(
            "jordan",
            List.of(
                new Offer(
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
  }

  @Test
  void publishOrderPhaseShouldVoidOrderCancelledBySeller() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createSkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1001, 3);
    createReservedOrder(
        "jordan", "83663", "awaiting_payment", "mtg#scryfall#scryfall-1#normal#NM", 1001, 1, 2);

    factory
        .fakeFetchTcgService()
        .seedOffers("jordan", List.of(cancelledOffer(83663, "CANCELLED_BY_SELLER")));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    assertThat(getJob("jordan", "job1").getStatus()).isEqualTo("succeeded");

    var order = getOrder("jordan", "83663");
    assertThat(order.getStatus()).isEqualTo("voided");
    assertThat(order.getFetchtcgStatus()).isEqualTo("CANCELLED_BY_SELLER");
    assertThat(order.getFetchtcgCurrentAction()).isNull();

    var units = getUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    assertThat(units).allSatisfy(unit -> assertThat(unit.status()).isEqualTo(UnitStatus.IN_STOCK));
    assertThat(units).allSatisfy(unit -> assertThat(unit.reservationId()).isNull());

    // the released units are dirty stock again, so the listing phase restores the full quantity
    var sku = getSku("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    assertThat(isDirty("jordan", sku.skuId())).isFalse();
  }

  @Test
  void publishOrderPhaseShouldVoidOrderCancelledByBuyer() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createSkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1001, 2);
    createReservedOrder(
        "jordan", "83663", "awaiting_payment", "mtg#scryfall#scryfall-1#normal#NM", 1001, 1);

    factory
        .fakeFetchTcgService()
        .seedOffers("jordan", List.of(cancelledOffer(83663, "CANCELLED_BY_BUYER")));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order.getStatus()).isEqualTo("voided");
    assertThat(order.getFetchtcgStatus()).isEqualTo("CANCELLED_BY_BUYER");

    var units = getUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    assertThat(units).allSatisfy(unit -> assertThat(unit.status()).isEqualTo(UnitStatus.IN_STOCK));
  }

  @Test
  void publishOrderPhaseShouldThrowForMissingOffer() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createSkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1001, 2);
    createReservedOrder(
        "jordan", "83663", "awaiting_payment", "mtg#scryfall#scryfall-1#normal#NM", 1001, 1);

    factory.fakeFetchTcgService().seedOffers("jordan", List.of());

    // act
    assertThatThrownBy(
            () -> jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("FetchTCG offer not found for order 83663");

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order.getStatus()).isEqualTo("awaiting_payment");

    var units = getUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    var reserved = units.stream().filter(u -> u.status() == UnitStatus.RESERVED).toList();
    assertThat(reserved).hasSize(1);
    assertThat(reserved.get(0).reservationId()).isEqualTo("83663");
  }

  @Test
  void publishOrderPhaseShouldNotVoidPaidOrder() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createSkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1001, 2);
    createReservedOrder("jordan", "83663", "to_pick", "mtg#scryfall#scryfall-1#normal#NM", 1001, 1);

    factory
        .fakeFetchTcgService()
        .seedOffers("jordan", List.of(cancelledOffer(83663, "CANCELLED_BY_SELLER")));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order.getStatus()).isEqualTo("to_pick");

    var units = getUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    var reserved = units.stream().filter(u -> u.status() == UnitStatus.RESERVED).toList();
    assertThat(reserved).hasSize(1);
  }

  @Test
  void publishOrderPhaseShouldNotReleaseAgainWhenOrderAlreadyVoided() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createSkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1001, 2);
    createReservedOrder("jordan", "83663", "voided", "mtg#scryfall#scryfall-1#normal#NM", 1001, 1);
    releaseUnit("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1);

    factory
        .fakeFetchTcgService()
        .seedOffers("jordan", List.of(cancelledOffer(83663, "CANCELLED_BY_SELLER")));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order.getStatus()).isEqualTo("voided");

    var units = getUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    assertThat(units).allSatisfy(unit -> assertThat(unit.status()).isEqualTo(UnitStatus.IN_STOCK));
  }

  @Test
  void publishOrderPhaseShouldResumePartiallyAppliedVoidingOrder() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createSkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1001, 2);
    createReservedOrder(
        "jordan", "83663", "voiding", "mtg#scryfall#scryfall-1#normal#NM", 1001, 1, 2);

    // simulate a prior delivery that released one unit before stopping
    factory
        .inventoryService()
        .releaseUnit(
            new RequestContext("jordan"),
            new ReleaseUnitRequest("mtg#scryfall#scryfall-1#normal#NM", 1, "83663"));

    factory
        .fakeFetchTcgService()
        .seedOffers("jordan", List.of(cancelledOffer(83663, "CANCELLED_BY_SELLER")));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order.getStatus()).isEqualTo("voided");

    var units = getUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    assertThat(units).allSatisfy(unit -> assertThat(unit.status()).isEqualTo(UnitStatus.IN_STOCK));
  }

  @Test
  void publishOrderPhaseShouldReleaseLargeOrderAcrossTransactions() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");

    var orderLines = new ArrayList<OrderRowItem>();
    for (int i = 1; i <= 60; i++) {
      var skuId = "mtg#scryfall#scryfall-" + i + "#normal#NM";
      createSkuWithUnits("jordan", skuId, 1000 + i, 1);
      reserveUnit("jordan", skuId, 1, "91329");
      orderLines.add(new OrderRowItem(skuId, 1000 + i, 1, "0.50", "0.50", List.of(1)));
    }
    createOrderWithLines("jordan", "91329", "awaiting_payment", orderLines);

    factory
        .fakeFetchTcgService()
        .seedOffers("jordan", List.of(cancelledOffer(91329, "CANCELLED_BY_SELLER")));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    assertThat(getJob("jordan", "job1").getStatus()).isEqualTo("succeeded");

    var order = getOrder("jordan", "91329");
    assertThat(order.getStatus()).isEqualTo("voided");

    for (int i = 1; i <= 60; i++) {
      var units = getUnits("jordan", "mtg#scryfall#scryfall-" + i + "#normal#NM");
      assertThat(units).hasSize(1);
      assertThat(units.get(0).status()).isEqualTo(UnitStatus.IN_STOCK);
      assertThat(units.get(0).reservationId()).isNull();
    }
  }

  @Test
  void publishOrderPhaseShouldRefreshFulfillmentDetailsOnExistingOrders() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createExistingOrder("jordan", "91329", "awaiting_payment");

    factory
        .fakeFetchTcgService()
        .seedOffers(
            "jordan",
            List.of(
                new Offer(
                    91329,
                    "ACCEPTED",
                    "SEND_TRACKING_CODE",
                    "2026-08-29T03:03:55.019+0000",
                    "DELIVERY",
                    "Chris Andrew (generic)",
                    new BuyerAddress(
                        "32 Abercrombie Street", null, "Howick", "Auckland", "2014", "NZ"),
                    new ShippingOption("Economy Tracked"),
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
  }

  @Test
  void publishOrderPhaseShouldStoreNoAddressWhenEveryPartIsBlank() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createExistingOrder("jordan", "83663", "awaiting_payment");

    factory
        .fakeFetchTcgService()
        .seedOffers(
            "jordan",
            List.of(
                new Offer(
                    83663,
                    "ACCEPTED",
                    "AWAITING_PAYMENT",
                    "2026-08-11T04:42:12.476+0000",
                    "PICKUP",
                    "Ben Creagh (sideswipe)",
                    new BuyerAddress(null, "", null, null, null, null),
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
    factory
        .fakeFetchTcgService()
        .seedOffers(
            "jordan",
            List.of(
                new Offer(
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

    factory
        .fakeFetchTcgService()
        .seedOffers(
            "jordan",
            List.of(
                new Offer(
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

    factory
        .fakeFetchTcgService()
        .seedOffers(
            "jordan",
            List.of(
                new Offer(
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
  void publishOrderPhaseShouldFailJobForInsufficientStock() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createSkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1001, 1);

    factory
        .fakeFetchTcgService()
        .seedOffers(
            "jordan",
            List.of(
                new Offer(
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
                        new OfferItem(
                            new OfferListing(1001, "raw-nm", new BigDecimal("2.00")),
                            3,
                            new BigDecimal("1.50"))))));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    assertThat(getJob("jordan", "job1").getStatus()).isEqualTo("failed");
    assertThat(getJob("jordan", "job1").getError())
        .isEqualTo("No in-stock units are available for this SKU");
    assertThat(getOrder("jordan", "83663").getStatus()).isEqualTo("reserving");
    assertThat(getUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM"))
        .singleElement()
        .extracting(Unit::status)
        .isEqualTo(UnitStatus.RESERVED);
  }

  @Test
  void publishOrderPhaseShouldSkipOffersBeforeCutoff() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createSkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1001, 3);
    createTrackOrdersAfter("jordan", Instant.parse("2026-08-15T00:00:00Z"));

    factory
        .fakeFetchTcgService()
        .seedOffers(
            "jordan",
            List.of(
                new Offer(
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
                        new OfferItem(
                            new OfferListing(1001, "raw-nm", new BigDecimal("2.00")),
                            2,
                            new BigDecimal("1.50"))))));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order).isNull();

    var units = getUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    assertThat(units.stream().allMatch(u -> u.status() == UnitStatus.IN_STOCK)).isTrue();
  }

  @Test
  void publishOrderPhaseShouldSkipOffersWithNullAcceptedAtWhenCutoffSet() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createSkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1001, 3);
    createTrackOrdersAfter("jordan", Instant.parse("2026-08-01T00:00:00Z"));

    factory
        .fakeFetchTcgService()
        .seedOffers(
            "jordan",
            List.of(
                new Offer(
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
                        new OfferItem(
                            new OfferListing(1001, "raw-nm", new BigDecimal("2.00")),
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

    factory
        .fakeFetchTcgService()
        .seedOffers(
            "jordan",
            List.of(
                new Offer(
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
                        new OfferItem(
                            new OfferListing(1001, "raw-nm", new BigDecimal("2.00")),
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
  void publishOrderPhaseShouldReserveMixedMagicAndPokemonOffer() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    var magicSkuId = "mtg#scryfall#scryfall-1#normal#NM";
    var pokemonSkuId = "pokemon#tcgplayer#283917#reverse_holofoil#NM";
    createSkuWithUnits("jordan", magicSkuId, 1001, 1);
    createInventorySku("jordan", pokemonSkuId, "fetch-card-9002", "12.00", true);
    seedUnits("jordan", pokemonSkuId, List.of(inStockUnit(1)));
    synchronizeListing("jordan", pokemonSkuId, 2002);
    factory
        .fakeFetchTcgService()
        .seedOffers(
            "jordan",
            List.of(
                new Offer(
                    83663,
                    "ACCEPTED",
                    null,
                    "2026-08-11T04:42:12.476+0000",
                    "PICKUP",
                    null,
                    null,
                    null,
                    new BigDecimal("14.00"),
                    List.of(
                        new OfferItem(
                            new OfferListing(1001, "raw-nm", new BigDecimal("2.00")),
                            1,
                            new BigDecimal("2.00")),
                        new OfferItem(
                            new OfferListing(2002, "raw-nm", new BigDecimal("12.00")),
                            1,
                            new BigDecimal("12.00"))))));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order.getStatus()).isEqualTo("awaiting_payment");
    assertThat(findOrderRows(order))
        .extracting(OrderRowItem::getSkuId)
        .containsExactly(magicSkuId, pokemonSkuId);
    assertThat(getUnits("jordan", magicSkuId))
        .singleElement()
        .satisfies(
            unit -> {
              assertThat(unit.status()).isEqualTo(UnitStatus.RESERVED);
              assertThat(unit.reservationId()).isEqualTo("83663");
            });
    assertThat(getUnits("jordan", pokemonSkuId))
        .singleElement()
        .satisfies(
            unit -> {
              assertThat(getSku("jordan", pokemonSkuId).game()).isEqualTo("pokemon");
              assertThat(unit.status()).isEqualTo(UnitStatus.RESERVED);
              assertThat(unit.reservationId()).isEqualTo("83663");
            });
  }

  @Test
  void publishOrderPhaseShouldCreateOrderAcceptedAfterCutoff() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createSkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1001, 3);
    createTrackOrdersAfter("jordan", Instant.parse("2026-08-10T00:00:00Z"));

    factory
        .fakeFetchTcgService()
        .seedOffers(
            "jordan",
            List.of(
                new Offer(
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
                        new OfferItem(
                            new OfferListing(1001, "raw-nm", new BigDecimal("2.00")),
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
  void publishOrderPhaseShouldReserveLargeOfferWithPerUnitTransactions() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");

    var offerItems = new ArrayList<OfferItem>();
    for (int i = 1; i <= 60; i++) {
      createSkuWithUnitAtSequence(
          "jordan", "mtg#scryfall#scryfall-" + i + "#normal#NM", 2000 + i, i);
      offerItems.add(
          new OfferItem(
              new OfferListing(2000 + i, "raw-nm", new BigDecimal("0.50")),
              1,
              new BigDecimal("0.50")));
    }

    factory
        .fakeFetchTcgService()
        .seedOffers(
            "jordan",
            List.of(
                new Offer(
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
    var orderLines = findOrderRows(order);
    assertThat(orderLines).hasSize(60);
    var allocated =
        orderLines.stream().flatMap(l -> l.getAllocatedSequenceNumbers().stream()).toList();
    assertThat(allocated)
        .containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, 60).boxed().toList());

    for (int i = 1; i <= 60; i++) {
      var units = getUnits("jordan", "mtg#scryfall#scryfall-" + i + "#normal#NM");
      assertThat(units).hasSize(1);
      assertThat(units.get(0).status()).isEqualTo(UnitStatus.RESERVED);
      assertThat(units.get(0).reservationId()).isEqualTo("91329");
    }
  }

  @Test
  void publishOrderPhaseShouldFailForMissingReservingOffer() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    var skuId = "mtg#scryfall#scryfall-1#normal#NM";
    createSkuWithUnits("jordan", skuId, 1001, 3);
    var firstUnit = getUnits("jordan", skuId).get(0);
    seedUnitState("jordan", skuId, firstUnit, UnitStatus.RESERVED, "83663");
    orderTable.putItem(
        createOrderItem(
            "jordan",
            "83663",
            "reserving",
            "ACCEPTED",
            null,
            "PICKUP",
            null,
            null,
            null,
            "3.33",
            List.of(new OrderRowItem(skuId, 1001, 2, "3.00", "2.00", List.of(1))),
            Instant.ofEpochSecond(1700000000)));

    // act & assert
    assertThatThrownBy(
            () -> jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("FetchTCG offer not found for order 83663");
    var order = getOrder("jordan", "83663");
    assertThat(order.getStatus()).isEqualTo("reserving");
    assertThat(findOrderRows(order).getFirst().getAllocatedSequenceNumbers()).containsExactly(1);
    assertThat(getUnits("jordan", skuId))
        .filteredOn(unit -> unit.status() == UnitStatus.RESERVED)
        .hasSize(1);
  }

  @Test
  void publishOrderPhaseShouldFailWhenPartialReservationHasNoAvailableStock() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    var skuId = "mtg#scryfall#scryfall-1#normal#NM";
    createSkuWithUnits("jordan", skuId, 1001, 1);
    var unit = getUnits("jordan", skuId).get(0);
    seedUnitState("jordan", skuId, unit, UnitStatus.SOLD, "another-order");
    orderTable.putItem(
        createOrderItem(
            "jordan",
            "83663",
            "reserving",
            "ACCEPTED",
            null,
            "PICKUP",
            null,
            null,
            null,
            "1.50",
            List.of(new OrderRowItem(skuId, 1001, 1, "1.50", "2.00", List.of())),
            Instant.ofEpochSecond(1700000000)));

    factory
        .fakeFetchTcgService()
        .seedOffers(
            "jordan",
            List.of(
                new Offer(
                    83663, "ACCEPTED", null, null, "PICKUP", null, null, null, null, List.of())));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    assertThat(getJob("jordan", "job1").getStatus()).isEqualTo("failed");
    assertThat(getOrder("jordan", "83663").getStatus()).isEqualTo("reserving");
  }

  @Test
  void publishPhaseShouldCreateListingForDirtySku() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createDirtySkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 2, "1.50");

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var sku = getSku("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    assertThat(isDirty("jordan", sku.skuId())).isFalse();
    assertThat(
            factory
                .listingService()
                .findListings(
                    new RequestContext("jordan"), new FindListingsRequest(List.of(900000)))
                .listings())
        .extracting(Listing::skuId)
        .containsExactly("mtg#scryfall#scryfall-1#normal#NM");
  }

  @Test
  void publishPhaseShouldUseStoredFetchTcgCardIdForPokemonProductSku() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    var skuId = "pokemon#tcgplayer#283917#reverse_holofoil#NM";
    createInventorySku("jordan", skuId, "fetch-card-9002", "12.00", true);
    seedUnits("jordan", skuId, List.of(inStockUnit(42)));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    assertThat(getJob("jordan", "job1").getStatus()).isEqualTo("succeeded");
    assertThat(getSku("jordan", skuId).externalId()).isEqualTo("283917");
    assertThat(getSku("jordan", skuId).fetchtcgCardId()).isEqualTo("fetch-card-9002");
  }

  private void createDirtySkuWithUnits(
      String user, String skuId, int unitCount, String suggestedPrice) {
    createInventorySku(user, skuId, "mtg_168_c_dom_normal", suggestedPrice, true);
    seedUnits(
        user,
        skuId,
        IntStream.rangeClosed(1, unitCount).mapToObj(sequence -> inStockUnit(sequence)).toList());
  }

  private void createPublishJob(String user, String jobId) {
    var jobItem =
        JobItem.create(user, jobId, "publish", null, null, Instant.ofEpochSecond(1700000000));
    jobTable.putItem(jobItem);
  }

  private void createDirtySkuWithUnit(String user, String skuId, int sequenceNumber) {
    createInventorySku(user, skuId, "mtg_168_c_dom_normal", "1.50", true);
    seedUnits(user, skuId, List.of(inStockUnit(sequenceNumber)));
  }

  private void createSkuWithUnits(String user, String skuId, int fetchTcgListingId, int unitCount) {
    createInventorySku(user, skuId, "mtg_168_c_dom_normal", "1.50", true);
    seedUnits(
        user,
        skuId,
        IntStream.rangeClosed(1, unitCount).mapToObj(sequence -> inStockUnit(sequence)).toList());
    synchronizeListing(user, skuId, fetchTcgListingId);
  }

  private void createSkuWithUnitAtSequence(
      String user, String skuId, int fetchTcgListingId, int sequenceNumber) {
    createInventorySku(user, skuId, "mtg_168_c_dom_normal", "1.50", true);
    seedUnits(user, skuId, List.of(inStockUnit(sequenceNumber)));
    synchronizeListing(user, skuId, fetchTcgListingId);
  }

  private Sku createInventorySku(
      String user, String skuId, String fetchTcgCardId, String suggestedPrice, boolean dirty) {
    var parts = skuId.split("#");
    var game = parts[0];
    var externalId = parts[2];
    var finish = parts[3];
    var condition = Condition.valueOf(parts[4]);
    var sku =
        new Sku(
            skuId,
            game,
            externalId,
            "Test Card",
            "dom",
            "Dominaria",
            "168",
            finish,
            condition,
            suggestedPrice == null ? null : new BigDecimal(suggestedPrice),
            fetchTcgCardId,
            "mtg".equals(game) ? 2624 : 1,
            1);
    factory.fakeInventoryService().seedSku(user, sku, dirty);
    return sku;
  }

  private Unit inStockUnit(int sequenceNumber) {
    return new Unit(
        sequenceNumber,
        "A0-" + sequenceNumber,
        UnitStatus.IN_STOCK,
        List.of(),
        Instant.ofEpochSecond(1700000000).getEpochSecond(),
        null,
        null);
  }

  private void seedUnits(String user, String skuId, List<Unit> units) {
    factory.fakeInventoryService().seedUnits(user, skuId, units);
  }

  private void seedUnitState(
      String user, String skuId, Unit current, UnitStatus status, String reservationId) {
    var updated =
        new Unit(
            current.sequenceNumber(),
            current.location(),
            status,
            current.photos(),
            current.createdAt(),
            current.updatedAt(),
            reservationId);
    var units = new ArrayList<>(getUnits(user, skuId));
    units.replaceAll(unit -> unit.sequenceNumber() == current.sequenceNumber() ? updated : unit);
    seedUnits(user, skuId, units);
  }

  private void createTrackOrdersAfter(String user, Instant trackOrdersAfter) {
    factory
        .dynamoDbClient()
        .putItem(
            PutItemRequest.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .item(
                    Map.of(
                        "pk", AttributeValue.fromS("USER#" + user),
                        "sk", AttributeValue.fromS("ORDER_SETTINGS"),
                        "user", AttributeValue.fromS(user),
                        "track_orders_after",
                            AttributeValue.fromN(Long.toString(trackOrdersAfter.getEpochSecond()))))
                .build());
  }

  private void createExistingOrder(String user, String offerId, String status) {
    var order =
        createOrderItem(
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
            List.of(),
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
            new OrderRowItem(
                skuId, fetchtcgListingId, allocated.size(), "3.33", "4.20", allocated)));
  }

  private void createOrderWithLines(
      String user, String offerId, String status, List<OrderRowItem> lines) {
    var order =
        createOrderItem(
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
            lines,
            Instant.ofEpochSecond(1700000000));
    orderTable.putItem(order);
  }

  private void reserveUnit(String user, String skuId, int sequenceNumber, String orderId) {
    seedUnitState(user, skuId, getUnit(user, skuId, sequenceNumber), UnitStatus.RESERVED, orderId);
  }

  private void releaseUnit(String user, String skuId, int sequenceNumber) {
    seedUnitState(user, skuId, getUnit(user, skuId, sequenceNumber), UnitStatus.IN_STOCK, null);
  }

  private Unit getUnit(String user, String skuId, int sequenceNumber) {
    return getUnits(user, skuId).stream()
        .filter(unit -> unit.sequenceNumber() == sequenceNumber)
        .findFirst()
        .orElseThrow();
  }

  private static Offer cancelledOffer(int offerId, String status) {
    return new Offer(
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
            .partitionValue(OrderItem.formatPk(user))
            .sortValue(OrderItem.formatSk(offerId))
            .build());
  }

  private Sku getSku(String user, String skuId) {
    try {
      return factory
          .inventoryService()
          .getSku(new RequestContext(user), new GetSkuRequest(skuId))
          .sku();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private List<Unit> getUnits(String user, String skuId) {
    try {
      var units = new ArrayList<Unit>();
      String continuation = null;
      do {
        var response =
            factory
                .inventoryService()
                .findUnits(
                    new RequestContext(user), new FindUnitsRequest(skuId, 100, continuation));
        units.addAll(response.units());
        continuation = response.nextContinuation();
      } while (continuation != null);
      return units;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private boolean isDirty(String user, String skuId) {
    try {
      return factory
          .inventoryService()
          .findSkus(new RequestContext(user), new FindSkusRequest(null, null, true, 100, null))
          .skus()
          .stream()
          .anyMatch(sku -> sku.skuId().equals(skuId));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void seedDefaultCardForDom168() {
    fakeFetchTcgService.seedSearchResult(
        "mtg", 2624, "Card 1", "normal", List.of(new CardSearchResult("mtg_168_c_dom_normal")));
    seedCard(
        "mtg_168_c_dom_normal",
        new GetCardResponse(
            "mtg_168_c_dom_normal",
            "Card 1",
            new BigDecimal("1.50"),
            Map.of("scryfallId", SCRYFALL_ID)));
  }

  private void seedCard(String cardId, GetCardResponse response) {
    factory.fakeFetchTcgService().seedCard(cardId, response);
  }

  private AppraiseJobProcessor newAppraiseJobProcessor() {
    return new AppraiseJobProcessor(
        importTable,
        importRowTable,
        fakeClock,
        factory.fakeFetchTcgService(),
        factory.pricingService());
  }

  private AppraiseJobProcessor newAppraiseJobProcessor(
      FetchTcgService fetchTcgService, PricingService pricingService) {
    return new AppraiseJobProcessor(
        importTable, importRowTable, fakeClock, fetchTcgService, pricingService);
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
    for (var row : rows) {
      var sets =
          switch (row.setCode()) {
            case "dom" -> List.of(new CardSet(2624, "Dominaria"));
            case "sta" -> List.of(new CardSet(3243, "Strixhaven Mystical Archive"));
            case "ltr" ->
                List.of(new CardSet(3268, "The Lord of the Rings: Tales of Middle-earth"));
            default -> List.<CardSet>of();
          };
      factory.fakeFetchTcgService().seedSets("mtg", row.setCode(), sets);
    }
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
              spec.scryfallId(),
              spec.language());
      importRowTable.putItem(rowItem);
    }
  }

  private void createImportWithNRows(String user, String importId, int rowCount) {
    factory.fakeFetchTcgService().seedSets("mtg", "dom", List.of(new CardSet(2624, "Dominaria")));
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
              "Card 1",
              "dom",
              "Dominaria",
              "168",
              "normal",
              "NM",
              SCRYFALL_ID,
              "en");
      importRowTable.putItem(rowItem);
    }
  }

  private JobItem createJob(
      String user, String jobId, String jobType, String status, String importId) {
    var jobItem =
        JobItem.create(user, jobId, jobType, importId, null, Instant.ofEpochSecond(1700000000));
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
            .partitionValue(ImportItem.formatPk(user))
            .sortValue(ImportItem.formatSk(importId))
            .build());
  }

  private JobItem getJob(String user, String jobId) {
    return jobTable.getItem(
        Key.builder()
            .partitionValue(JobItem.formatPk(user))
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

  private void synchronizeListing(String user, String skuId, int listingId) {
    var sku = getSku(user, skuId);
    factory
        .fakeFetchTcgService()
        .seedListingId(
            user, sku.fetchtcgCardId(), "raw-" + sku.condition().name().toLowerCase(), listingId);
    try {
      factory
          .listingService()
          .updateListing(new RequestContext(user), new UpdateListingRequest(skuId));
    } catch (ServiceException e) {
      throw new RuntimeException(e);
    }
  }

  private OrderItem createOrderItem(
      String user,
      String orderId,
      String status,
      String providerStatus,
      String action,
      String delivery,
      String buyer,
      OrderItem.BuyerAddress address,
      String postage,
      String total,
      List<OrderRowItem> lines,
      Instant createdAt) {
    var table = TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), OrderRowItem.class);
    for (int position = 0; position < lines.size(); position++) {
      var line = lines.get(position);
      table.putItem(
          OrderRowItem.create(
              user,
              orderId,
              position,
              line.getSkuId(),
              line.getFetchtcgListingId(),
              line.getQuantity(),
              line.getPrice(),
              line.getListedPrice(),
              line.getAllocatedSequenceNumbers()));
    }
    return OrderItem.create(
        user,
        orderId,
        status,
        providerStatus,
        action,
        delivery,
        buyer,
        address,
        postage,
        total,
        lines.size(),
        createdAt);
  }

  private List<OrderRowItem> findOrderRows(OrderItem order) {
    return TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), OrderRowItem.class)
        .query(
            request ->
                request
                    .queryConditional(
                        QueryConditional.keyEqualTo(
                            Key.builder()
                                .partitionValue(order.getPk() + "#ORDER#" + order.getOrderId())
                                .build()))
                    .consistentRead(true))
        .items()
        .stream()
        .toList();
  }
}
