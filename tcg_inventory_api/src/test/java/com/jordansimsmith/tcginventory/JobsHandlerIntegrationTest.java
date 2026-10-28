package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.queue.FakeQueueClient;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.fetchtcg.FakeFetchTcgClient;
import com.jordansimsmith.tcginventory.fetchtcg.FetchTcgClient;
import com.jordansimsmith.tcginventory.fetchtcg.FetchTcgSetMapping;
import com.jordansimsmith.tcginventory.fetchtcg.client.BuyerAddress;
import com.jordansimsmith.tcginventory.fetchtcg.client.CardListing;
import com.jordansimsmith.tcginventory.fetchtcg.client.FakeFetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindCardListingsRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindCardListingsResponse;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetAccountRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetAccountResponse;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetCardRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetCardResponse;
import com.jordansimsmith.tcginventory.fetchtcg.client.Offer;
import com.jordansimsmith.tcginventory.fetchtcg.client.OfferItem;
import com.jordansimsmith.tcginventory.fetchtcg.client.OfferListing;
import com.jordansimsmith.tcginventory.fetchtcg.client.ShippingOption;
import com.jordansimsmith.tcginventory.imports.AppraiseJobProcessor;
import com.jordansimsmith.tcginventory.imports.ImportItem;
import com.jordansimsmith.tcginventory.imports.ImportRowItem;
import com.jordansimsmith.tcginventory.inventory.SkuItem;
import com.jordansimsmith.tcginventory.inventory.UnitItem;
import com.jordansimsmith.tcginventory.orders.OrderItem;
import com.jordansimsmith.tcginventory.pricing.PricingItem;
import com.jordansimsmith.tcginventory.pricing.PricingRepository;
import com.jordansimsmith.tcginventory.publish.ListingPhaseProcessor;
import com.jordansimsmith.tcginventory.settings.SettingsItem;
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
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

@Testcontainers
public class JobsHandlerIntegrationTest {

  private FakeClock fakeClock;
  private FakeQueueClient<JobMessage> fakeJobsQueue;
  private FakeFetchTcgClient fakeFetchTcgClient;
  private FakeFetchTcgService fakeFetchTcgService;
  private ObjectMapper objectMapper;
  private DynamoDbTable<ImportItem> importTable;
  private DynamoDbTable<ImportRowItem> importRowTable;
  private DynamoDbTable<JobItem> jobTable;
  private DynamoDbTable<SkuItem> skuTable;
  private DynamoDbTable<UnitItem> unitTable;
  private DynamoDbTable<SettingsItem> settingsTable;
  private DynamoDbTable<OrderItem> orderTable;
  private DynamoDbTable<AuditItem> auditTable;
  private PricingRepository pricingRepository;
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
    fakeFetchTcgClient = factory.fakeFetchTcgClient();
    fakeFetchTcgService = factory.fakeFetchTcgService();
    objectMapper = factory.objectMapper();
    importTable = factory.importTable();
    importRowTable = factory.importRowTable();
    jobTable = factory.jobTable();
    skuTable = factory.skuTable();
    unitTable = factory.unitTable();
    settingsTable = factory.settingsTable();
    orderTable = factory.orderTable();
    auditTable = factory.auditTable();
    pricingRepository = new PricingRepository(factory);

    DynamoDbUtils.reset(factory.dynamoDbClient());
    fakeJobsQueue.reset();
    fakeFetchTcgClient.reset();
    fakeFetchTcgService.reset();

    jobsHandler = new JobsHandler(factory);
  }

  @Test
  void appraiseShouldResolveIdentityAndKeep() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportWithRow("jordan", "import1", "dom", "168", "normal", "NM", "en");
    createJob("jordan", "job1", "appraise", "queued", "import1");

    fakeFetchTcgClient.seedSearchResult(
        "mtg",
        2624,
        "Card 1",
        "normal",
        new FetchTcgClient.SearchCardsResponse(
            List.of(new FetchTcgClient.SearchCard("mtg_168_c_dom_normal"))));
    seedCard(
        "mtg_168_c_dom_normal",
        new FetchTcgClient.GetCardResponse(
            "mtg_168_c_dom_normal",
            "Card 1",
            Map.of("NZ", new FetchTcgClient.PricingData(new BigDecimal("0.30"))),
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
  void appraiseShouldExcludeTheFetchTcgUsersOwnListingFromCompetitorPrices() {
    createImportWithRow("jordan", "import1", "dom", "168", "normal", "NM", "en");
    var jobItem = createJob("jordan", "job1", "appraise", "queued", "import1");
    pricingRepository.update(PricingItem.create("jordan", "0.00"));
    seedDefaultCardForDom168();
    seedCard(
        "mtg_168_c_dom_normal",
        new FetchTcgClient.GetCardResponse(
            "mtg_168_c_dom_normal",
            "Card 1",
            Map.of("NZ", new FetchTcgClient.PricingData(new BigDecimal("1.00"))),
            Map.of("scryfallId", SCRYFALL_ID)));
    factory
        .fakeFetchTcgService()
        .seedListings(
            "mtg_168_c_dom_normal",
            List.of(
                new CardListing(1, "raw-nm", new BigDecimal("0.40"), "jordan"),
                new CardListing(2, "raw-nm", new BigDecimal("0.95"), "rival1")));

    newAppraiseJobProcessor(factory.fakeFetchTcgService()).processBatch("jordan", jobItem);

    assertThat(getRow("jordan", "import1", 1).getSuggestedPrice()).isEqualTo("0.90");
  }

  @Test
  void appraiseShouldResolveSellerIdentityIndependentlyForEachUser() {
    createImportWithRow("jordan", "import1", "dom", "168", "normal", "NM", "en");
    var jordanJob = createJob("jordan", "jordan-job", "appraise", "queued", "import1");
    createImportWithRow("other-user", "import2", "dom", "168", "normal", "NM", "en");
    var otherUserJob = createJob("other-user", "other-user-job", "appraise", "queued", "import2");
    fakeFetchTcgClient.seedSearchResult(
        "mtg",
        2624,
        "Card 1",
        "normal",
        new FetchTcgClient.SearchCardsResponse(
            List.of(new FetchTcgClient.SearchCard("mtg_168_c_dom_normal"))));
    seedCard(
        "mtg_168_c_dom_normal",
        new FetchTcgClient.GetCardResponse(
            "mtg_168_c_dom_normal",
            "Card 1",
            Map.of("NZ", new FetchTcgClient.PricingData(new BigDecimal("1.00"))),
            Map.of("scryfallId", SCRYFALL_ID)));
    factory
        .fakeFetchTcgService()
        .seedListings(
            "mtg_168_c_dom_normal",
            List.of(
                new CardListing(1, "raw-nm", new BigDecimal("0.40"), "jordan"),
                new CardListing(2, "raw-nm", new BigDecimal("0.95"), "other-user")));

    factory.fakeFetchTcgService().seedSellerUsername("jordan", "jordan");
    newAppraiseJobProcessor(factory.fakeFetchTcgService()).processBatch("jordan", jordanJob);
    factory.fakeFetchTcgService().seedSellerUsername("other-user", "other-user");
    newAppraiseJobProcessor(factory.fakeFetchTcgService()).processBatch("other-user", otherUserJob);

    assertThat(getRow("jordan", "import1", 1).getSuggestedPrice()).isEqualTo("0.90");
    assertThat(getRow("other-user", "import2", 1).getSuggestedPrice()).isEqualTo("1.00");
  }

  @Test
  void appraiseShouldPreserveFetchTcgAccountNotFoundMessage() throws Exception {
    createImportWithRow("jordan", "import1", "dom", "168", "normal", "NM", "en");
    var jobItem = createJob("jordan", "job1", "appraise", "queued", "import1");
    var fetchTcgService = mock(FetchTcgService.class);
    when(fetchTcgService.getAccount(new RequestContext("jordan"), new GetAccountRequest()))
        .thenThrow(new NotFoundException("missing"));

    var result = newAppraiseJobProcessor(fetchTcgService).processBatch("jordan", jobItem);

    assertThat(result).isEqualTo(new JobProcessor.FailureJobResult("missing"));
    assertThat(getRow("jordan", "import1", 1).getDecision()).isNull();
    assertThat(getImport("jordan", "import1").getError()).isEqualTo("missing");
  }

  @Test
  void appraiseShouldUseServiceMarketPriceAfterCatalogResolution() throws Exception {
    // arrange
    createImportWithRow("jordan", "import1", "dom", "168", "normal", "NM", "en");
    var jobItem = createJob("jordan", "job1", "appraise", "queued", "import1");
    fakeFetchTcgClient.seedSearchResult(
        "mtg",
        2624,
        "Card 1",
        "normal",
        new FetchTcgClient.SearchCardsResponse(
            List.of(new FetchTcgClient.SearchCard("mtg_168_c_dom_normal"))));
    seedCard(
        "mtg_168_c_dom_normal",
        new FetchTcgClient.GetCardResponse(
            "mtg_168_c_dom_normal",
            "Card 1",
            Map.of("NZ", new FetchTcgClient.PricingData(new BigDecimal("0.30"))),
            Map.of("scryfallId", SCRYFALL_ID)));
    var fetchTcgService = mock(FetchTcgService.class);
    var ctx = new RequestContext("jordan");
    when(fetchTcgService.getAccount(ctx, new GetAccountRequest()))
        .thenReturn(new GetAccountResponse("jordan", 0));
    when(fetchTcgService.getCard(ctx, new GetCardRequest("mtg_168_c_dom_normal")))
        .thenReturn(
            new GetCardResponse(
                "mtg_168_c_dom_normal", "Card 1", new BigDecimal("1.20"), Map.of()));
    when(fetchTcgService.findCardListings(
            ctx, new FindCardListingsRequest("mtg_168_c_dom_normal", 100, null)))
        .thenReturn(new FindCardListingsResponse(List.of(), null));

    // act
    newAppraiseJobProcessor(fetchTcgService).processBatch("jordan", jobItem);

    // assert
    verify(fetchTcgService).getAccount(ctx, new GetAccountRequest());
    verify(fetchTcgService).getCard(ctx, new GetCardRequest("mtg_168_c_dom_normal"));
    verify(fetchTcgService)
        .findCardListings(ctx, new FindCardListingsRequest("mtg_168_c_dom_normal", 100, null));
    verifyNoMoreInteractions(fetchTcgService);
    var row = getRow("jordan", "import1", 1);
    assertThat(row.getMarketPrice()).isEqualTo("1.20");
    assertThat(row.getSuggestedPrice()).isEqualTo("1.20");
  }

  @Test
  void appraiseShouldUseDefaultFloorWhenStoredPricingItemHasNoFloor() {
    // arrange
    createImportWithRow("jordan", "import1", "dom", "168", "normal", "NM", "en");
    var jobItem = createJob("jordan", "job1", "appraise", "queued", "import1");
    pricingRepository.update(PricingItem.create("jordan", null));
    fakeFetchTcgClient.seedSearchResult(
        "mtg",
        2624,
        "Card 1",
        "normal",
        new FetchTcgClient.SearchCardsResponse(
            List.of(new FetchTcgClient.SearchCard("mtg_168_c_dom_normal"))));
    seedCard(
        "mtg_168_c_dom_normal",
        new FetchTcgClient.GetCardResponse(
            "mtg_168_c_dom_normal",
            "Card 1",
            Map.of("NZ", new FetchTcgClient.PricingData(new BigDecimal("0.30"))),
            Map.of("scryfallId", SCRYFALL_ID)));
    factory
        .fakeFetchTcgService()
        .seedListings(
            "mtg_168_c_dom_normal",
            List.of(new CardListing(1, "raw-nm", new BigDecimal("0.25"), "rival1")));

    // act
    newAppraiseJobProcessor(factory.fakeFetchTcgService()).processBatch("jordan", jobItem);

    // assert
    assertThat(getRow("jordan", "import1", 1).getSuggestedPrice()).isEqualTo("0.25");
    assertThat(pricingRepository.get("jordan").getPriceFloor()).isNull();
  }

  @Test
  void appraiseShouldApplyConfiguredFloorAcrossContinuationAndPreserveDecidedRows() {
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
    pricingRepository.update(PricingItem.create("jordan", "2.00"));
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
    fakeFetchTcgClient.seedSearchResult(
        "mtg",
        3243,
        "Card 1",
        "foil",
        new FetchTcgClient.SearchCardsResponse(
            List.of(new FetchTcgClient.SearchCard("etched-card"))));
    seedCard(
        "etched-card",
        new FetchTcgClient.GetCardResponse(
            "etched-card",
            "Card 1 (Foil Etched)",
            Map.of("NZ", new FetchTcgClient.PricingData(new BigDecimal("1.50"))),
            Map.of("scryfallId", SCRYFALL_ID)));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    var row = getRow("jordan", "import1", 1);
    assertThat(row.getDecision()).isEqualTo("keep");
    assertThat(row.getFinish()).isEqualTo("etched");
    assertThat(row.getFetchtcgCardId()).isEqualTo("etched-card");
    assertThat(fakeFetchTcgClient.getSearchCallCount())
        .isEqualTo(FetchTcgSetMapping.get("sta").size());
  }

  @Test
  void appraiseShouldKeepFoilSearchFinishUnchanged() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportWithRow("jordan", "import1", "dom", "168", "foil", "NM", "en");
    createJob("jordan", "job1", "appraise", "queued", "import1");
    fakeFetchTcgClient.seedSearchResult(
        "mtg",
        2624,
        "Card 1",
        "foil",
        new FetchTcgClient.SearchCardsResponse(
            List.of(new FetchTcgClient.SearchCard("foil-card"))));
    seedCard(
        "foil-card",
        new FetchTcgClient.GetCardResponse(
            "foil-card",
            "Card 1",
            Map.of("NZ", new FetchTcgClient.PricingData(new BigDecimal("1.50"))),
            Map.of("scryfallId", SCRYFALL_ID)));

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
    assertThatThrownBy(
            () ->
                newAppraiseJobProcessor(factory.fakeFetchTcgService())
                    .processBatch("jordan", jobItem))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("unsupported finish for game: foil-etched");
    assertThat(fakeFetchTcgClient.getSearchCallCount()).isZero();
  }

  @Test
  void appraiseShouldReviewAmbiguousEtchedCardsWithoutFetchingListings() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportWithRow("jordan", "import1", "sta", "13", "etched", "NM", "en");
    createJob("jordan", "job1", "appraise", "queued", "import1");
    fakeFetchTcgClient.seedSearchResult(
        "mtg",
        3243,
        "Card 1",
        "foil",
        new FetchTcgClient.SearchCardsResponse(
            List.of(
                new FetchTcgClient.SearchCard("regular-foil-card"),
                new FetchTcgClient.SearchCard("etched-card"))));
    seedCard(
        "regular-foil-card",
        new FetchTcgClient.GetCardResponse(
            "regular-foil-card",
            "Card 1",
            Map.of("NZ", new FetchTcgClient.PricingData(new BigDecimal("1.50"))),
            Map.of("scryfallId", SCRYFALL_ID)));
    seedCard(
        "etched-card",
        new FetchTcgClient.GetCardResponse(
            "etched-card",
            "Card 1 (Foil Etched)",
            Map.of("NZ", new FetchTcgClient.PricingData(new BigDecimal("2.50"))),
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

    fakeFetchTcgClient.seedSearchResult(
        "mtg",
        2624,
        "Card 1",
        "normal",
        new FetchTcgClient.SearchCardsResponse(
            List.of(new FetchTcgClient.SearchCard("mtg_168_c_dom_normal"))));
    seedCard(
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
        "mtg",
        2624,
        "Card 1",
        "normal",
        new FetchTcgClient.SearchCardsResponse(
            List.of(
                new FetchTcgClient.SearchCard("mtg_168_c_dom_normal"),
                new FetchTcgClient.SearchCard("mtg_410_c_dom_B_normal"))));
    seedCard(
        "mtg_168_c_dom_normal",
        new FetchTcgClient.GetCardResponse(
            "mtg_168_c_dom_normal",
            "Card 1",
            Map.of("NZ", new FetchTcgClient.PricingData(new BigDecimal("1.50"))),
            Map.of("scryfallId", "scryfall-other")));
    seedCard(
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
            SCRYFALL_ID,
            "en"));
    createJob("jordan", "job1", "appraise", "queued", "import1");

    fakeFetchTcgClient.seedSearchResult(
        "mtg",
        3268,
        "Troll of Khazad-dum",
        "normal",
        new FetchTcgClient.SearchCardsResponse(
            List.of(new FetchTcgClient.SearchCard("mtg_111_c_ltr_normal"))));
    seedCard(
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
        "mtg",
        2624,
        "Card 1",
        "normal",
        new FetchTcgClient.SearchCardsResponse(
            List.of(new FetchTcgClient.SearchCard("mtg_168_c_dom_normal"))));
    seedCard(
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
            "not/a-uuid",
            "en"));
    var jobItem = createJob("jordan", "job1", "appraise", "queued", "import1");

    // act & assert
    assertThatThrownBy(
            () ->
                newAppraiseJobProcessor(factory.fakeFetchTcgService())
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
        "mtg",
        2624,
        "Card 1",
        "normal",
        new FetchTcgClient.SearchCardsResponse(
            List.of(new FetchTcgClient.SearchCard("mtg_168_c_dom_normal"))));
    seedCard(
        "mtg_168_c_dom_normal",
        new FetchTcgClient.GetCardResponse(
            "mtg_168_c_dom_normal",
            "Card 1",
            Map.of("NZ", new FetchTcgClient.PricingData(new BigDecimal("1.50"))),
            Map.of("scryfallId", SCRYFALL_ID)));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    assertThat(fakeFetchTcgClient.getSearchCallCount())
        .isEqualTo(FetchTcgSetMapping.get("dom").size());

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
    var orderLines = order.getLines();
    assertThat(orderLines).hasSize(1);
    assertThat(orderLines.get(0).getSkuId()).isEqualTo("mtg#scryfall#scryfall-1#normal#NM");
    assertThat(orderLines.get(0).getPrice()).isEqualTo("1.50");
    assertThat(orderLines.get(0).getListedPrice()).isEqualTo("2.00");
    assertThat(orderLines.get(0).getQuantity()).isEqualTo(2);

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

    var paymentAudits =
        getAuditEntries("jordan").stream().filter(a -> "payment".equals(a.getEventType())).toList();
    assertThat(paymentAudits).hasSize(1);
    assertThat(paymentAudits.get(0).getOrderId()).isEqualTo("83663");
    assertThat(paymentAudits.get(0).getBeforeStatus()).isEqualTo("awaiting_payment");
    assertThat(paymentAudits.get(0).getAfterStatus()).isEqualTo("to_pick");

    createPublishJob("jordan", "job2");
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job2", "publish"), null);

    var replayPaymentAudits =
        getAuditEntries("jordan").stream().filter(a -> "payment".equals(a.getEventType())).toList();
    assertThat(replayPaymentAudits).hasSize(1);
    assertThat(getOrder("jordan", "83663").getStatus()).isEqualTo("to_pick");
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
    assertThat(units).allSatisfy(unit -> assertThat(unit.getStatus()).isEqualTo("in_stock"));
    assertThat(units).allSatisfy(unit -> assertThat(unit.getOrderId()).isNull());

    var releaseAudits =
        getAuditEntries("jordan").stream().filter(a -> "release".equals(a.getEventType())).toList();
    assertThat(releaseAudits).hasSize(2);
    assertThat(releaseAudits)
        .allSatisfy(audit -> assertThat(audit.getOrderId()).isEqualTo("83663"));
    assertThat(getAuditEntries("jordan"))
        .filteredOn(audit -> "order_voided".equals(audit.getEventType()))
        .hasSize(1);

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

    factory.fakeFetchTcgService().seedOffers("jordan", List.of());

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

    factory
        .fakeFetchTcgService()
        .seedOffers("jordan", List.of(cancelledOffer(83663, "CANCELLED_BY_SELLER")));

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

    factory
        .fakeFetchTcgService()
        .seedOffers("jordan", List.of(cancelledOffer(83663, "CANCELLED_BY_SELLER")));

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
  void publishOrderPhaseShouldResumePartiallyAppliedVoidingOrder() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    createSkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1001, 2);
    createReservedOrder(
        "jordan", "83663", "voiding", "mtg#scryfall#scryfall-1#normal#NM", 1001, 1, 2);

    // simulate a prior delivery that released one unit before stopping
    releaseUnit("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1);

    factory.fakeFetchTcgService().seedOffers("jordan", List.of());

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
    assertThat(releaseAudits.get(0).getSequenceNumber()).isEqualTo(2);
    assertThat(getAuditEntries("jordan"))
        .filteredOn(audit -> "order_voided".equals(audit.getEventType()))
        .hasSize(1);
  }

  @Test
  void publishOrderPhaseShouldReleaseLargeOrderAcrossTransactions() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");

    var orderLines = new ArrayList<OrderItem.OrderLine>();
    for (int i = 1; i <= 60; i++) {
      var skuId = "mtg#scryfall#scryfall-" + i + "#normal#NM";
      createSkuWithUnits("jordan", skuId, 1000 + i, 1);
      reserveUnit("jordan", skuId, 1, "91329");
      orderLines.add(new OrderItem.OrderLine(skuId, 1000 + i, 1, "0.50", "0.50", List.of(1)));
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
      assertThat(units.get(0).getStatus()).isEqualTo("in_stock");
      assertThat(units.get(0).getOrderId()).isNull();
    }

    var releaseAudits =
        getAuditEntries("jordan").stream().filter(a -> "release".equals(a.getEventType())).toList();
    assertThat(releaseAudits).hasSize(60);
    assertThat(getAuditEntries("jordan"))
        .filteredOn(audit -> "order_voided".equals(audit.getEventType()))
        .hasSize(1);
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
        .contains("Offer 83663", "listing 1001", "in stock");
    assertThat(getOrder("jordan", "83663")).isNull();
    assertThat(getUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM"))
        .singleElement()
        .extracting(UnitItem::getStatus)
        .isEqualTo("in_stock");
    assertThat(getAuditEntries("jordan"))
        .noneMatch(audit -> "reserve".equals(audit.getEventType()));
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
    assertThat(units.stream().allMatch(u -> "in_stock".equals(u.getStatus()))).isTrue();
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
    var pokemonSku =
        SkuItem.create(
            "jordan",
            pokemonSkuId,
            "pokemon",
            "283917",
            "reverse_holofoil",
            "NM",
            "Abomasnow",
            "lost-origin",
            "Lost Origin",
            "043/196",
            "fetch-card-9002",
            "12.00");
    pokemonSku.setDirty(false);
    pokemonSku.setGsi1pk(SkuItem.USER_PREFIX + "jordan#CLEAN");
    pokemonSku.setFetchtcgListingId(2002);
    skuTable.putItem(pokemonSku);
    unitTable.putItem(
        UnitItem.create(
            "jordan",
            "pokemon",
            pokemonSkuId,
            1,
            "in_stock",
            "pokemon-import",
            Instant.ofEpochSecond(1700000000)));
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
    assertThat(order.getLines())
        .extracting(OrderItem.OrderLine::getSkuId)
        .containsExactly(magicSkuId, pokemonSkuId);
    assertThat(getUnits("jordan", magicSkuId))
        .singleElement()
        .satisfies(
            unit -> {
              assertThat(unit.getStatus()).isEqualTo("reserved");
              assertThat(unit.getOrderId()).isEqualTo("83663");
            });
    assertThat(getUnits("jordan", pokemonSkuId))
        .singleElement()
        .satisfies(
            unit -> {
              assertThat(unit.getGame()).isEqualTo("pokemon");
              assertThat(unit.getStatus()).isEqualTo("reserved");
              assertThat(unit.getOrderId()).isEqualTo("83663");
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
    var orderLines = order.getLines();
    assertThat(orderLines).hasSize(60);
    var allocated =
        orderLines.stream().flatMap(l -> l.getAllocatedSequenceNumbers().stream()).toList();
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
    assertThat(reserveAudits).hasSize(60);
    assertThat(reserveAudits)
        .allSatisfy(
            audit -> {
              assertThat(audit.getOrderId()).isEqualTo("91329");
              assertThat(audit.getBeforeStatus()).isEqualTo("in_stock");
              assertThat(audit.getAfterStatus()).isEqualTo("reserved");
            });
    assertThat(getAuditEntries("jordan"))
        .filteredOn(audit -> "order_reserved".equals(audit.getEventType()))
        .hasSize(1);
  }

  @Test
  void publishOrderPhaseShouldResumeSavedReservationWithoutOffer() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    var skuId = "mtg#scryfall#scryfall-1#normal#NM";
    createSkuWithUnits("jordan", skuId, 1001, 3);
    var firstUnit = getUnits("jordan", skuId).get(0);
    firstUnit.setStatus("reserved");
    firstUnit.setOrderId("83663");
    unitTable.putItem(firstUnit);
    orderTable.putItem(
        OrderItem.create(
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
            List.of(new OrderItem.OrderLine(skuId, 1001, 2, "3.00", "2.00", List.of(1, 2))),
            Instant.ofEpochSecond(1700000000)));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order).isNotNull();
    assertThat(order.getStatus()).isEqualTo("awaiting_payment");
    var orderLines = order.getLines();
    assertThat(orderLines).hasSize(1);
    assertThat(orderLines.get(0).getQuantity()).isEqualTo(2);
    assertThat(orderLines.get(0).getAllocatedSequenceNumbers()).containsExactly(1, 2);

    var units = getUnits("jordan", skuId);
    var reserved = units.stream().filter(u -> "reserved".equals(u.getStatus())).toList();
    assertThat(reserved).hasSize(2);
    assertThat(reserved).allSatisfy(u -> assertThat(u.getOrderId()).isEqualTo("83663"));
    var inStock = units.stream().filter(u -> "in_stock".equals(u.getStatus())).toList();
    assertThat(inStock).hasSize(1);
    assertThat(inStock.get(0).getSequenceNumber()).isEqualTo(3);
    assertThat(getAuditEntries("jordan"))
        .filteredOn(audit -> "reserve".equals(audit.getEventType()))
        .hasSize(1);
    assertThat(getAuditEntries("jordan"))
        .filteredOn(audit -> "order_reserved".equals(audit.getEventType()))
        .hasSize(1);
  }

  @Test
  void publishOrderPhaseShouldPropagateSavedReservationConflictForRetry() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    var skuId = "mtg#scryfall#scryfall-1#normal#NM";
    createSkuWithUnits("jordan", skuId, 1001, 1);
    var unit = getUnits("jordan", skuId).get(0);
    unit.setStatus("sold");
    unit.setOrderId("another-order");
    unitTable.putItem(unit);
    orderTable.putItem(
        OrderItem.create(
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
            List.of(new OrderItem.OrderLine(skuId, 1001, 1, "1.50", "2.00", List.of(1))),
            Instant.ofEpochSecond(1700000000)));

    // act
    assertThatThrownBy(
            () -> jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null))
        .isInstanceOf(TransactionCanceledException.class);

    // assert
    assertThat(getJob("jordan", "job1").getStatus()).isEqualTo("running");
    assertThat(getOrder("jordan", "83663").getStatus()).isEqualTo("reserving");
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
  }

  @Test
  void publishPhaseShouldUseStoredFetchTcgCardIdForPokemonProductSku() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createPublishJob("jordan", "job1");
    var skuId = "pokemon#tcgplayer#283917#reverse_holofoil#NM";
    skuTable.putItem(
        SkuItem.create(
            "jordan",
            skuId,
            "pokemon",
            "283917",
            "reverse_holofoil",
            "NM",
            "Abomasnow",
            "lost-origin",
            "Lost Origin",
            "043/196",
            "fetch-card-9002",
            "12.00"));
    unitTable.putItem(
        UnitItem.create(
            "jordan",
            "pokemon",
            skuId,
            42,
            "in_stock",
            "pokemon-import",
            Instant.ofEpochSecond(1700000000)));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    assertThat(getJob("jordan", "job1").getStatus()).isEqualTo("succeeded");
    assertThat(getSku("jordan", skuId).getExternalId()).isEqualTo("283917");
    assertThat(getSku("jordan", skuId).getFetchtcgCardId()).isEqualTo("fetch-card-9002");
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

    // assert - the listing snapshot was not written because the condition failed
    var updated = getSku("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    assertThat(updated.getFetchtcgListingId()).isNull();
    assertThat(updated.getLastPublishedQuantity()).isNull();
  }

  private void createDirtySkuWithUnits(
      String user, String skuId, int unitCount, String suggestedPrice) {
    var parts = skuId.split("#");
    var skuItem =
        SkuItem.create(
            user,
            skuId,
            parts[0],
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
    var jobItem =
        JobItem.create(user, jobId, "publish", null, null, Instant.ofEpochSecond(1700000000));
    jobTable.putItem(jobItem);
  }

  private void createDirtySkuWithUnit(String user, String skuId, int sequenceNumber) {
    var parts = skuId.split("#");
    var skuItem =
        SkuItem.create(
            user,
            skuId,
            parts[0],
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
            new OrderItem.OrderLine(
                skuId, fetchtcgListingId, allocated.size(), "3.33", "4.20", allocated)));
  }

  private void createOrderWithLines(
      String user, String offerId, String status, List<OrderItem.OrderLine> lines) {
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
            lines,
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
        "mtg",
        2624,
        "Card 1",
        "normal",
        new FetchTcgClient.SearchCardsResponse(
            List.of(new FetchTcgClient.SearchCard("mtg_168_c_dom_normal"))));
    seedCard(
        "mtg_168_c_dom_normal",
        new FetchTcgClient.GetCardResponse(
            "mtg_168_c_dom_normal",
            "Card 1",
            Map.of("NZ", new FetchTcgClient.PricingData(new BigDecimal("1.50"))),
            Map.of("scryfallId", SCRYFALL_ID)));
  }

  private void seedCard(String cardId, FetchTcgClient.GetCardResponse response) {
    fakeFetchTcgClient.seedCard(cardId, response);
    var nzPricing = response.pricingData() == null ? null : response.pricingData().get("NZ");
    factory
        .fakeFetchTcgService()
        .seedCard(
            new GetCardResponse(
                response.id(),
                response.name(),
                nzPricing == null ? null : nzPricing.tcgMarketPrice(),
                response.externalReferences() == null ? Map.of() : response.externalReferences()));
  }

  private AppraiseJobProcessor newAppraiseJobProcessor(FetchTcgService fetchTcgService) {
    return new AppraiseJobProcessor(
        importTable,
        importRowTable,
        fakeClock,
        fetchTcgService,
        fakeFetchTcgClient,
        pricingRepository);
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
