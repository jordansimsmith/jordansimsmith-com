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
import com.jordansimsmith.tcginventory.fetchtcg.client.CardListing;
import com.jordansimsmith.tcginventory.fetchtcg.client.CardSearchResult;
import com.jordansimsmith.tcginventory.fetchtcg.client.CardSet;
import com.jordansimsmith.tcginventory.fetchtcg.client.FakeFetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetCardResponse;
import com.jordansimsmith.tcginventory.imports.AppraiseJobProcessor;
import com.jordansimsmith.tcginventory.imports.ImportItem;
import com.jordansimsmith.tcginventory.imports.ImportRowItem;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import com.jordansimsmith.tcginventory.pricing.client.GetPriceRequest;
import com.jordansimsmith.tcginventory.pricing.client.GetPriceResponse;
import com.jordansimsmith.tcginventory.pricing.client.GetPricingRequest;
import com.jordansimsmith.tcginventory.pricing.client.PricingService;
import com.jordansimsmith.tcginventory.pricing.client.UpdatePricingRequest;
import com.jordansimsmith.time.FakeClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
public class JobsHandlerIntegrationTest {

  private FakeClock fakeClock;
  private FakeQueueClient<JobMessage> fakeJobsQueue;
  private FakeFetchTcgService fakeFetchTcgService;
  private ObjectMapper objectMapper;
  private DynamoDbTable<ImportItem> importTable;
  private DynamoDbTable<ImportRowItem> importRowTable;
  private DynamoDbTable<JobItem> jobTable;
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
        JobItem.create("jordan", "job1", "appraise", "import1", Instant.ofEpochSecond(1700000000));
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
}
