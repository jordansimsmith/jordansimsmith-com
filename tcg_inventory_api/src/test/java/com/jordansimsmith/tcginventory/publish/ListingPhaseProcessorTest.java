package com.jordansimsmith.tcginventory.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import com.jordansimsmith.tcginventory.inventory.client.FakeInventoryService;
import com.jordansimsmith.tcginventory.inventory.client.FindSkusRequest;
import com.jordansimsmith.tcginventory.inventory.client.FindSkusResponse;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.inventory.client.Sku;
import com.jordansimsmith.tcginventory.listings.client.FakeListingService;
import com.jordansimsmith.tcginventory.listings.client.ListingService;
import com.jordansimsmith.tcginventory.listings.client.UpdateListingRequest;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

public class ListingPhaseProcessorTest {
  private static final RequestContext CTX = new RequestContext("alice");

  @Test
  void processShouldDrainFullBatchThenRestartAtHead() throws Exception {
    // arrange
    var inventory = new FakeInventoryService();
    for (int i = 0; i < 102; i++) inventory.seedSku("alice", sku("sku-" + i), true);
    var processor = new ListingPhaseProcessor(inventory, new FakeListingService(inventory));
    // act
    var first = processor.process("alice", 0);
    var second = processor.process("alice", 100);
    var empty = processor.process("alice", 102);
    // assert
    assertThat(first.processedCount()).isEqualTo(100);
    assertThat(first.continuation()).isEqualTo("100");
    assertThat(second.processedCount()).isEqualTo(102);
    assertThat(second.continuation()).isNull();
    assertThat(empty.processedCount()).isEqualTo(102);
    assertThat(empty.continuation()).isNull();
  }

  @Test
  void processShouldContinueAfterShortPageWithoutFetchingAnotherPage() throws Exception {
    // arrange
    var inventory = mock(InventoryService.class);
    var listings = mock(ListingService.class);
    var skus = IntStream.range(0, 99).mapToObj(i -> sku("sku-" + i)).toList();
    when(inventory.findSkus(CTX, new FindSkusRequest(null, null, true, 100, null)))
        .thenReturn(new FindSkusResponse(skus, "opaque"));
    // act
    var result = new ListingPhaseProcessor(inventory, listings).process("alice", 10);
    // assert
    assertThat(result.processedCount()).isEqualTo(109);
    assertThat(result.continuation()).isEqualTo("109");
    var order = inOrder(inventory, listings);
    order.verify(inventory).findSkus(CTX, new FindSkusRequest(null, null, true, 100, null));
    for (int i = 0; i < 99; i++)
      order.verify(listings).updateListing(CTX, new UpdateListingRequest("sku-" + i));
    verifyNoMoreInteractions(inventory);
  }

  @Test
  void processShouldPropagateCollectionAndSynchronizationFailures() throws Exception {
    // arrange
    var inventory = mock(InventoryService.class);
    var listings = mock(ListingService.class);
    var failure = new ServiceUnavailableException("unavailable");
    when(inventory.findSkus(any(), any())).thenThrow(failure);
    var processor = new ListingPhaseProcessor(inventory, listings);
    // act / assert
    assertThatThrownBy(() -> processor.process("alice", 0)).isSameAs(failure);
    verifyNoInteractions(listings);
    doReturn(new FindSkusResponse(List.of(sku("a"), sku("b"), sku("c")), null))
        .when(inventory)
        .findSkus(any(), any());
    when(listings.updateListing(CTX, new UpdateListingRequest("b"))).thenThrow(failure);
    assertThatThrownBy(() -> processor.process("alice", 0)).isSameAs(failure);
    verify(listings).updateListing(CTX, new UpdateListingRequest("a"));
    verify(listings, never()).updateListing(CTX, new UpdateListingRequest("c"));
  }

  private static Sku sku(String id) {
    return new Sku(
        id, "mtg", "external", id, "set", "Set", "1", "normal", Condition.NM, null, "card", 1, 0);
  }
}
