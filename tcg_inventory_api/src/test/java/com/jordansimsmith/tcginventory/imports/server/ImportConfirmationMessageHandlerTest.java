package com.jordansimsmith.tcginventory.imports.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.games.client.FakeGamesService;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import com.jordansimsmith.tcginventory.inventory.client.FakeInventoryService;
import com.jordansimsmith.tcginventory.inventory.client.GetSkuRequest;
import com.jordansimsmith.tcginventory.inventory.client.GetUnitRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImportConfirmationMessageHandlerTest {
  private static final String USER = "context-user";
  private static final String IMPORT_ID = "opaque/import:id";
  private static final Instant NOW = Instant.ofEpochSecond(1_700_000_000);

  @Test
  void handlerCreatesKeepRowsWithStableIdentityAndPersistsPlacement() throws Exception {
    var reads = mock(ImportReadRepository.class);
    var writes = mock(ImportMutationRepository.class);
    var inventory = inventory();
    var ctx = new RequestContext(USER);
    var parent = new ImportItem();
    parent.setGame(FakeGamesService.MAGIC_THE_GATHERING.id());
    parent.setStatus("confirming");
    var keep = row(7, "keep", "external/id");
    keep.setName("Card");
    keep.setSetCode("set/code");
    keep.setSetName("Set");
    keep.setCollectorNumber("collector/7");
    keep.setFinish("etched");
    keep.setCondition("LP");
    keep.setSuggestedPrice("12.30");
    keep.setFetchtcgCardId("fetch/card");
    keep.setFetchtcgSetId(42);
    keep.setPhotos(
        List.of(ImportRowItem.Photo.create("photo-2"), ImportRowItem.Photo.create("photo-1")));
    var discard = row(8, "discard", "discard-card");
    var alreadyCreated = row(9, "keep", "already-created-card");
    alreadyCreated.setSkuId("saved-sku");
    when(reads.getImport(USER, IMPORT_ID)).thenReturn(parent);
    when(reads.findRows(USER, IMPORT_ID)).thenReturn(List.of(keep, discard, alreadyCreated));
    var handler = new ImportConfirmationMessageHandler(reads, writes, inventory, () -> NOW);

    handler.handleMessage(
        ctx, new ImportMessage("different-payload-user", IMPORT_ID, ImportMessageType.CONFIRM));

    var skuId = "mtg#scryfall#external/id#etched#LP";
    var sku = inventory.getSku(ctx, new GetSkuRequest(skuId)).sku();
    assertThat(sku.game()).isEqualTo("mtg");
    assertThat(sku.externalId()).isEqualTo("external/id");
    assertThat(sku.name()).isEqualTo("Card");
    assertThat(sku.setCode()).isEqualTo("set/code");
    assertThat(sku.setName()).isEqualTo("Set");
    assertThat(sku.collectorNumber()).isEqualTo("collector/7");
    assertThat(sku.finish()).isEqualTo("etched");
    assertThat(sku.condition()).isEqualTo(Condition.LP);
    assertThat(sku.price()).isEqualByComparingTo(new BigDecimal("12.30"));
    assertThat(sku.fetchtcgCardId()).isEqualTo("fetch/card");
    assertThat(sku.fetchtcgSetId()).isEqualTo(42);

    assertThat(inventory.getUnit(ctx, new GetUnitRequest(skuId, 0)).unit().photos())
        .containsExactly("photo-2", "photo-1");
    assertNotCreated(inventory, ctx, "mtg#scryfall#discard-card#normal#NM");
    assertNotCreated(inventory, ctx, "mtg#scryfall#already-created-card#normal#NM");
    verify(writes).recordCreatedUnit(USER, IMPORT_ID, 7, skuId, 0, "A0-0");
    verify(writes).finishConfirmation(USER, IMPORT_ID, NOW);
  }

  @Test
  void handlerReturnsForConfirmedImportsWithoutReadingRows() throws Exception {
    var reads = mock(ImportReadRepository.class);
    var writes = mock(ImportMutationRepository.class);
    var inventory = inventory();
    var parent = new ImportItem();
    parent.setStatus("confirmed");
    when(reads.getImport(USER, IMPORT_ID)).thenReturn(parent);

    handler(reads, writes, inventory).handleMessage(new RequestContext(USER), message());

    verify(reads, never()).findRows(any(), any());
    verifyNoInteractions(writes);
  }

  @Test
  void handlerRejectsWrongMessageTypeAndInvalidLifecycle() throws Exception {
    var reads = mock(ImportReadRepository.class);
    var writes = mock(ImportMutationRepository.class);
    var inventory = inventory();
    var parent = new ImportItem();
    parent.setStatus("review");
    when(reads.getImport(USER, IMPORT_ID)).thenReturn(parent);
    var handler = handler(reads, writes, inventory);
    var ctx = new RequestContext(USER);

    assertThatThrownBy(
            () ->
                handler.handleMessage(
                    ctx, new ImportMessage(USER, IMPORT_ID, ImportMessageType.APPRAISE)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> handler.handleMessage(ctx, message()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("confirmation import is not in confirming status");
    verify(reads, never()).findRows(any(), any());
    verifyNoInteractions(writes);
  }

  @Test
  void handlerFailsWhenTheParentIsMissing() {
    var reads = mock(ImportReadRepository.class);
    var writes = mock(ImportMutationRepository.class);
    when(reads.getImport(USER, IMPORT_ID)).thenReturn(null);

    assertThatThrownBy(
            () ->
                handler(reads, writes, inventory())
                    .handleMessage(new RequestContext(USER), message()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("confirmation import not found");
    verify(reads, never()).findRows(any(), any());
    verifyNoInteractions(writes);
  }

  @Test
  void handlerUsesContextUserAndEmptyPhotosWhenPhotoListIsMissing() throws Exception {
    var reads = mock(ImportReadRepository.class);
    var writes = mock(ImportMutationRepository.class);
    var inventory = inventory();
    var parent = new ImportItem();
    parent.setStatus("confirming");
    parent.setGame("mtg");
    var row = row(1, "keep", "photo-less-card");
    row.setFetchtcgSetId(42);
    row.setPhotos(null);
    when(reads.getImport(USER, IMPORT_ID)).thenReturn(parent);
    when(reads.findRows(USER, IMPORT_ID)).thenReturn(List.of(row));

    handler(reads, writes, inventory).handleMessage(new RequestContext(USER), message());

    var ctx = new RequestContext(USER);
    var skuId = "mtg#scryfall#photo-less-card#normal#NM";
    assertThat(inventory.getSku(ctx, new GetSkuRequest(skuId)).sku().price()).isNull();
    assertThat(inventory.getSku(ctx, new GetSkuRequest(skuId)).sku().fetchtcgSetId()).isEqualTo(42);
    assertThat(inventory.getUnit(ctx, new GetUnitRequest(skuId, 0)).unit().photos()).isEmpty();
    verify(reads).getImport(USER, IMPORT_ID);
  }

  private static void assertNotCreated(
      FakeInventoryService inventory, RequestContext ctx, String skuId) {
    assertThatThrownBy(() -> inventory.getUnit(ctx, new GetUnitRequest(skuId, 0)))
        .isInstanceOf(NotFoundException.class);
  }

  private static FakeInventoryService inventory() {
    return new FakeInventoryService(new FakeGamesService());
  }

  private static ImportConfirmationMessageHandler handler(
      ImportReadRepository reads, ImportMutationRepository writes, FakeInventoryService inventory) {
    return new ImportConfirmationMessageHandler(reads, writes, inventory, () -> NOW);
  }

  private static ImportMessage message() {
    return new ImportMessage(USER, IMPORT_ID, ImportMessageType.CONFIRM);
  }

  private static ImportRowItem row(int position, String decision, String externalId) {
    var row = new ImportRowItem();
    row.setPosition(position);
    row.setDecision(decision);
    row.setExternalId(externalId);
    row.setFinish("normal");
    row.setCondition("NM");
    row.setName(externalId);
    return row;
  }
}
