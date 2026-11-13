package com.jordansimsmith.tcginventory.scans.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import com.jordansimsmith.tcginventory.catalog.client.CatalogPrinting;
import com.jordansimsmith.tcginventory.catalog.client.CatalogService;
import com.jordansimsmith.tcginventory.catalog.client.FindCatalogCardsRequest;
import com.jordansimsmith.tcginventory.catalog.client.FindCatalogCardsResponse;
import com.jordansimsmith.tcginventory.imports.client.CreateImportRequest;
import com.jordansimsmith.tcginventory.imports.client.FindImportsRequest;
import com.jordansimsmith.tcginventory.imports.client.GetImportRequest;
import com.jordansimsmith.tcginventory.imports.client.ImportService;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;

@Testcontainers
class ScanConfirmationMessageHandlerIntegrationTest {
  @Container private static final DynamoDbContainer DYNAMODB = new DynamoDbContainer();

  private static final RequestContext CTX = new RequestContext("scan-owner");
  private static final ScanConfirmationMessage MESSAGE =
      new ScanConfirmationMessage(CTX.user(), "scan-1");
  private DynamoDbTable<ScanItem> scanTable;
  private ScanReadRepository reads;
  private ScanMutationRepository writes;
  private CatalogService catalog;
  private ImportService imports;
  private ScanConfirmationMessageHandler handler;

  @BeforeAll
  static void createTable() {
    var factory =
        TcgInventoryTestFactory.create(
            DYNAMODB.getEndpoint(), TcgInventoryTestFactory.UNUSED_S3_ENDPOINT);
    DynamoDbUtils.createTable(factory.dynamoDbClient(), factory.tableDefinition());
  }

  @BeforeEach
  void setUp() {
    var factory =
        TcgInventoryTestFactory.create(
            DYNAMODB.getEndpoint(), TcgInventoryTestFactory.UNUSED_S3_ENDPOINT);
    var dynamoDb = factory.dynamoDbClient();
    DynamoDbUtils.reset(dynamoDb);
    var enhanced = DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDb).build();
    var tableName = factory.tableDefinition().tableName();
    scanTable = enhanced.table(tableName, TableSchema.fromBean(ScanItem.class));
    var rowTable = enhanced.table(tableName, TableSchema.fromBean(ScanRowItem.class));
    reads = new ScanReadRepository(scanTable, rowTable);
    writes =
        spy(
            new ScanMutationRepository(
                scanTable, rowTable, dynamoDb, factory.fakeClock(), tableName));
    catalog = mock(CatalogService.class);
    imports = spy(factory.importService());
    handler = new ScanConfirmationMessageHandler(reads, writes, catalog, imports);
  }

  @Test
  void handleMessageShouldPreservePhysicalOrderAcrossCatalogBatches() throws Exception {
    // arrange
    var rows =
        IntStream.rangeClosed(1, 200).mapToObj(i -> scanRow(i, "id-" + Math.min(i, 199))).toList();
    saveScan(rows, "confirming");
    answerCatalog();

    // act
    handler.handleMessage(CTX, MESSAGE);
    handler.handleMessage(CTX, MESSAGE);

    // assert
    var scan = getScan();
    assertThat(scan.getStatus()).isEqualTo("confirmed");
    assertThat(scan.getRowCount()).isEqualTo(200);
    var detail = imports.getImport(CTX, new GetImportRequest(scan.getImportId()));
    assertThat(detail.filename()).isEqualTo("scan-1.scan");
    assertThat(detail.rows())
        .extracting(row -> row.externalId())
        .containsExactlyElementsOf(rows.stream().map(ScanRowItem::getSelectedExternalId).toList());
    assertThat(detail.rows().getFirst().name()).isEqualTo("Card id-1");
    assertThat(detail.rows().getFirst().setCode()).isEqualTo("dom");
    assertThat(detail.rows().getFirst().setName()).isEqualTo("Dominaria");
    assertThat(detail.rows().getFirst().collectorNumber()).isEqualTo("42");
    assertThat(detail.rows())
        .allMatch(row -> row.finish().equals("normal") && row.condition().name().equals("NM"));
    verify(catalog).findCards(CTX, request(1, 100));
    verify(catalog).findCards(CTX, request(101, 199));
    verify(imports).createImport(any(), any());
  }

  @Test
  void handleMessageShouldLookUpAllPrintingsAgainAndReplayTheImportsReceipt() throws Exception {
    // arrange
    saveScan(List.of(scanRow(1, "id-1"), scanRow(3, "id-2")), "confirming");
    answerCatalog();
    var storageFailure = DynamoDbException.builder().message("simulated storage failure").build();
    doThrow(storageFailure)
        .doCallRealMethod()
        .when(writes)
        .completeScanConfirmation(any(), any(), any());

    // act / assert
    assertThatThrownBy(() -> handler.handleMessage(CTX, MESSAGE)).isSameAs(storageFailure);
    assertThat(getScan().getStatus()).isEqualTo("confirming");
    assertThat(imports.findImports(CTX, new FindImportsRequest(null, null)).imports()).hasSize(1);
    var committedId =
        imports
            .findImports(CTX, new FindImportsRequest(null, null))
            .imports()
            .getFirst()
            .importId();

    handler.handleMessage(CTX, MESSAGE);

    assertThat(getScan().getStatus()).isEqualTo("confirmed");
    assertThat(getScan().getImportId()).isEqualTo(committedId);
    assertThat(imports.findImports(CTX, new FindImportsRequest(null, null)).imports()).hasSize(1);
    verify(catalog, times(2)).findCards(CTX, request(1, 2));
    verifyNoMoreInteractions(catalog);
    var captured = ArgumentCaptor.forClass(CreateImportRequest.class);
    verify(imports, times(2)).createImport(eq(CTX), captured.capture());
    assertThat(captured.getAllValues()).hasSize(2).containsOnly(captured.getValue());
    assertThat(captured.getValue().operationId()).isEqualTo(MESSAGE.scanId());
  }

  @Test
  void handleMessageShouldLeaveScanConfirmingWhenCatalogCannotResolveSelections() throws Exception {
    // arrange
    saveScan(List.of(scanRow(3, "id-1")), "confirming");
    when(catalog.findCards(any(), any()))
        .thenReturn(new FindCatalogCardsResponse(List.of()))
        .thenReturn(new FindCatalogCardsResponse(List.of(printing("id-1", "foil"))))
        .thenThrow(new ServiceUnavailableException("catalog unavailable"));

    // act / assert
    assertThatThrownBy(() -> handler.handleMessage(CTX, MESSAGE))
        .hasMessage("scan position 3: selected printing was not found");
    assertThatThrownBy(() -> handler.handleMessage(CTX, MESSAGE))
        .hasMessage("scan position 3: selected printing does not support finish normal");
    assertThatThrownBy(() -> handler.handleMessage(CTX, MESSAGE))
        .isInstanceOf(ServiceUnavailableException.class);
    assertThat(getScan().getStatus()).isEqualTo("confirming");
    verifyNoInteractions(imports);
  }

  @Test
  void handleMessageShouldFailWhenScanIsMissingOrHasUnexpectedStatus() {
    // act / assert
    assertThatThrownBy(() -> handler.handleMessage(CTX, MESSAGE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("scan scan-1: scan not found");
    saveScan(List.of(), "reviewing");
    assertThatThrownBy(() -> handler.handleMessage(CTX, MESSAGE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("scan scan-1: expected confirming status, got reviewing");

    // assert
    assertThat(getScan().getStatus()).isEqualTo("reviewing");
    verifyNoInteractions(catalog, imports);
  }

  @Test
  void handleMessageShouldFailWhenRetainedRowHasNoSelection() {
    // arrange
    saveScan(List.of(scanRow(1, "id-1"), scanRow(3, null)), "confirming");

    // act / assert
    assertThatThrownBy(() -> handler.handleMessage(CTX, MESSAGE))
        .hasMessage("scan position 3: missing selected printing ID");
    assertThat(getScan().getStatus()).isEqualTo("confirming");
    verifyNoInteractions(catalog, imports);
  }

  private ScanRowItem scanRow(int position, String externalId) {
    var row =
        ScanRowItem.create(
            CTX.user(), MESSAGE.scanId(), position, position + ".jpg", 1, "source/" + position);
    row.setSelectedExternalId(externalId);
    return row;
  }

  private void saveScan(List<ScanRowItem> rows, String status) {
    var scan =
        ScanItem.create(CTX.user(), "mtg", MESSAGE.scanId(), "NM", "normal", 200, Instant.EPOCH);
    scan.setStatus(status);
    scanTable.putItem(scan);
    for (var row : rows) {
      writes.createScanRow(row);
    }
  }

  private ScanItem getScan() {
    return reads.getScan(CTX.user(), MESSAGE.scanId());
  }

  private void answerCatalog() throws Exception {
    when(catalog.findCards(any(), any()))
        .thenAnswer(
            invocation -> {
              FindCatalogCardsRequest request = invocation.getArgument(1);
              return new FindCatalogCardsResponse(
                  request.externalIds().stream().map(id -> printing(id, "normal")).toList());
            });
  }

  private CatalogPrinting printing(String id, String finish) {
    return new CatalogPrinting("mtg", id, "Card " + id, "dom", "Dominaria", "42", List.of(finish));
  }

  private FindCatalogCardsRequest request(int first, int last) {
    return new FindCatalogCardsRequest(
        "mtg", IntStream.rangeClosed(first, last).mapToObj(i -> "id-" + i).toList());
  }
}
