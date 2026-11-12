package com.jordansimsmith.tcginventory.scans;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import com.jordansimsmith.tcginventory.catalog.CatalogCard;
import com.jordansimsmith.tcginventory.catalog.FakeCardCatalogs;
import com.jordansimsmith.tcginventory.imports.client.FakeImportService;
import com.jordansimsmith.tcginventory.imports.client.FindImportsRequest;
import com.jordansimsmith.tcginventory.imports.client.GetImportRequest;
import com.jordansimsmith.tcginventory.imports.client.ImportService;
import com.jordansimsmith.time.FakeClock;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ScanConfirmationProcessingIntegrationTest {
  private static final String USER = "scan-user";
  private static final String SCAN_ID = "opaque-scan-id";
  private static final String FIRST_CARD_ID = "opaque-card-one";
  private static final String SECOND_CARD_ID = "opaque-card-two";

  @Container private static final DynamoDbContainer DYNAMODB = new DynamoDbContainer();

  private TcgInventoryTestFactory factory;
  private FakeClock clock;
  private FakeCardCatalogs catalogs;
  private FakeImportService fakeImportService;
  private ScanRepository scans;

  @BeforeAll
  static void setUpDatabase() {
    var factory =
        TcgInventoryTestFactory.create(
            DYNAMODB.getEndpoint(), TcgInventoryTestFactory.UNUSED_S3_ENDPOINT);
    DynamoDbUtils.createTable(factory.dynamoDbClient(), factory.tableDefinition());
  }

  @BeforeEach
  void setUp() {
    factory =
        TcgInventoryTestFactory.create(
            DYNAMODB.getEndpoint(), TcgInventoryTestFactory.UNUSED_S3_ENDPOINT);
    DynamoDbUtils.reset(factory.dynamoDbClient());
    clock = factory.fakeClock();
    scans = factory.scanRepository();
    catalogs = factory.fakeCardCatalogs();
    catalogs.addCard(card(FIRST_CARD_ID, "First card", "1"));
    catalogs.addCard(card(SECOND_CARD_ID, "Second card", "2"));
    fakeImportService = new FakeImportService(factory.photosService());
    saveConfirmingScan(SCAN_ID, List.of(row(1, FIRST_CARD_ID), row(3, SECOND_CARD_ID)));
  }

  @Test
  void createsCanonicalImportAndSavesItsIdInFirstScannedOrder() throws Exception {
    var processor = processor(fakeImportService);

    processor.processBatch(USER, job(SCAN_ID));

    var scan = scans.getScan(USER, SCAN_ID);
    var detail =
        fakeImportService.getImport(
            new RequestContext(USER), new GetImportRequest(scan.getImportId()));
    assertThat(scan.getStatus()).isEqualTo("confirmed");
    assertThat(detail.game()).isEqualTo("mtg");
    assertThat(detail.filename()).isEqualTo(SCAN_ID + ".scan");
    assertThat(detail.rows())
        .extracting(row -> row.externalId())
        .containsExactly(FIRST_CARD_ID, SECOND_CARD_ID);
    assertThat(detail.rows())
        .extracting(row -> row.name())
        .containsExactly("First card", "Second card");
    assertThat(detail.rows())
        .allMatch(row -> row.condition().name().equals("NM") && row.finish().equals("normal"));
    assertThat(catalogs.lookupRequests()).containsExactly(List.of(FIRST_CARD_ID, SECOND_CARD_ID));
  }

  @Test
  void retryBeforeImportCommitCreatesOneImport() throws Exception {
    ImportService failingOnce = spy(fakeImportService);
    doThrow(new ServiceUnavailableException("imports unavailable"))
        .doCallRealMethod()
        .when(failingOnce)
        .createImport(any(), any());
    var processor = processor(failingOnce);

    assertThatThrownBy(() -> processor.processBatch(USER, job(SCAN_ID)))
        .isInstanceOf(ServiceUnavailableException.class);
    assertThat(scans.getScan(USER, SCAN_ID).getStatus()).isEqualTo("confirming");
    assertThat(
            fakeImportService
                .findImports(new RequestContext(USER), new FindImportsRequest(100, null))
                .imports())
        .isEmpty();

    processor.processBatch(USER, job(SCAN_ID));

    assertThat(scans.getScan(USER, SCAN_ID).getStatus()).isEqualTo("confirmed");
    assertThat(
            fakeImportService
                .findImports(new RequestContext(USER), new FindImportsRequest(100, null))
                .imports())
        .hasSize(1);
  }

  @Test
  void retryAfterImportCommitRecoversTheSameImport() throws Exception {
    ImportService failingOnce = spy(fakeImportService);
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              throw new ServiceUnavailableException("response lost after import commit");
            })
        .doCallRealMethod()
        .when(failingOnce)
        .createImport(any(), any());
    var processor = processor(failingOnce);

    assertThatThrownBy(() -> processor.processBatch(USER, job(SCAN_ID)))
        .isInstanceOf(ServiceUnavailableException.class);
    var originalImportId =
        fakeImportService
            .findImports(new RequestContext(USER), new FindImportsRequest(100, null))
            .imports()
            .getFirst()
            .importId();

    processor.processBatch(USER, job(SCAN_ID));

    assertThat(scans.getScan(USER, SCAN_ID).getImportId()).isEqualTo(originalImportId);
    assertThat(
            fakeImportService
                .findImports(new RequestContext(USER), new FindImportsRequest(100, null))
                .imports())
        .hasSize(1);
  }

  @Test
  void retryAfterSavingImportIdFailsUsesTheCreationReceipt() throws Exception {
    var failingOnce = spy(scans);
    doThrow(new IllegalStateException("scan write failed"))
        .doCallRealMethod()
        .when(failingOnce)
        .transitionScanToConfirmed(eq(USER), eq(SCAN_ID), anyString());
    var processor = processor(failingOnce, fakeImportService);

    assertThatThrownBy(() -> processor.processBatch(USER, job(SCAN_ID)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("scan write failed");
    var originalImportId =
        fakeImportService
            .findImports(new RequestContext(USER), new FindImportsRequest(100, null))
            .imports()
            .getFirst()
            .importId();

    processor.processBatch(USER, job(SCAN_ID));

    assertThat(failingOnce.getScan(USER, SCAN_ID).getImportId()).isEqualTo(originalImportId);
    assertThat(
            fakeImportService
                .findImports(new RequestContext(USER), new FindImportsRequest(100, null))
                .imports())
        .hasSize(1);
  }

  private ScanConfirmationJobProcessor processor(ImportService importService) {
    return processor(scans, importService);
  }

  private ScanConfirmationJobProcessor processor(
      ScanRepository scanRepository, ImportService importService) {
    return new ScanConfirmationJobProcessor(
        scanRepository, catalogs, factory.gamesService(), importService);
  }

  private void saveConfirmingScan(String scanId, List<ScanRowItem> rows) {
    var scan = ScanItem.create(USER, "mtg", scanId, "NM", "normal", rows.size(), clock.now());
    scan.setStatus("confirming");
    scans.createScan(scan, rows);
  }

  private static ScanRowItem row(int position, String cardId) {
    var row =
        ScanRowItem.create(
            USER, SCAN_ID, position, "%03d.jpg".formatted(position), 10, "unused-key");
    row.setSelectedExternalId(cardId);
    return row;
  }

  private static CatalogCard card(String externalId, String name, String collectorNumber) {
    return new CatalogCard(
        "mtg",
        externalId,
        name,
        "set",
        "Set name",
        collectorNumber,
        new CatalogCard.ImageUrls(null, null),
        List.of("normal"));
  }

  private static JobItem job(String scanId) {
    var job = new JobItem();
    job.setScanId(scanId);
    return job;
  }
}
