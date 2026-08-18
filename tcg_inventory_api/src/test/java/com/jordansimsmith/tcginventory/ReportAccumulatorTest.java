package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

public class ReportAccumulatorTest {

  @Test
  void addSkuShouldPreferLastPublishedPriceOverSuggestedPrice() {
    // arrange
    var accumulator = new ReportAccumulator();
    var sku = createSku("sku1", "2.00", "1.00");
    var units = List.of(createUnit("in_stock"));

    // act
    accumulator.addSku(sku, units);

    // assert
    var totals = accumulator.toTotals();
    assertThat(totals.inventoryValue()).isEqualTo("2.00");
    assertThat(totals.inStockUnits()).isEqualTo(1);
  }

  @Test
  void addSkuShouldFallBackToSuggestedPriceWhenLastPublishedPriceIsNull() {
    // arrange
    var accumulator = new ReportAccumulator();
    var sku = createSku("sku1", null, "1.50");
    var units = List.of(createUnit("in_stock"));

    // act
    accumulator.addSku(sku, units);

    // assert
    var totals = accumulator.toTotals();
    assertThat(totals.inventoryValue()).isEqualTo("1.50");
  }

  @Test
  void addSkuShouldCountUnpricedUnitsWhenBothPricesAreNull() {
    // arrange
    var accumulator = new ReportAccumulator();
    var sku = createSku("sku1", null, null);
    var units = List.of(createUnit("in_stock"), createUnit("in_stock"));

    // act
    accumulator.addSku(sku, units);

    // assert
    var totals = accumulator.toTotals();
    assertThat(totals.inventoryValue()).isEqualTo("0");
    assertThat(totals.unpricedUnits()).isEqualTo(2);
    assertThat(totals.inStockUnits()).isEqualTo(2);
  }

  @Test
  void addSkuShouldCountReservedUnitsOnlyInReservedCount() {
    // arrange
    var accumulator = new ReportAccumulator();
    var sku = createSku("sku1", "5.00", null);
    var units = List.of(createUnit("in_stock"), createUnit("reserved"), createUnit("reserved"));

    // act
    accumulator.addSku(sku, units);

    // assert
    var totals = accumulator.toTotals();
    assertThat(totals.inventoryValue()).isEqualTo("5.00");
    assertThat(totals.inStockUnits()).isEqualTo(1);
    assertThat(totals.reservedUnits()).isEqualTo(2);
  }

  @Test
  void addSkuShouldExcludeRemovedUnitsFromEverything() {
    // arrange
    var accumulator = new ReportAccumulator();
    var sku = createSku("sku1", "3.00", null);
    var units = List.of(createUnit("in_stock"), createUnit("removed"), createUnit("sold"));

    // act
    accumulator.addSku(sku, units);

    // assert
    var totals = accumulator.toTotals();
    assertThat(totals.inventoryValue()).isEqualTo("3.00");
    assertThat(totals.inStockUnits()).isEqualTo(1);
    assertThat(totals.soldUnits()).isEqualTo(1);
    assertThat(totals.reservedUnits()).isEqualTo(0);
    assertThat(totals.skuCount()).isEqualTo(1);
  }

  @Test
  void addOrderShouldOnlyCountPaidOrders() {
    // arrange
    var accumulator = new ReportAccumulator();

    // act
    accumulator.addOrder(createOrder("to_pick", "10.50"));
    accumulator.addOrder(createOrder("fulfilled", "5.25"));
    accumulator.addOrder(createOrder("awaiting_payment", "100.00"));
    accumulator.addOrder(createOrder("voided", "20.00"));

    // assert
    var totals = accumulator.toTotals();
    assertThat(totals.revenueToDate()).isEqualTo("15.75");
  }

  @Test
  void toTotalsShouldReturnZeroesWhenEmpty() {
    // arrange
    var accumulator = new ReportAccumulator();

    // act
    var totals = accumulator.toTotals();

    // assert
    assertThat(totals.inventoryValue()).isEqualTo("0");
    assertThat(totals.inStockUnits()).isEqualTo(0);
    assertThat(totals.skuCount()).isEqualTo(0);
    assertThat(totals.reservedUnits()).isEqualTo(0);
    assertThat(totals.soldUnits()).isEqualTo(0);
    assertThat(totals.revenueToDate()).isEqualTo("0");
    assertThat(totals.unpricedUnits()).isEqualTo(0);
  }

  @Test
  void addSkuShouldAccumulateValueAcrossMultipleSkus() {
    // arrange
    var accumulator = new ReportAccumulator();
    var sku1 = createSku("sku1", "1.50", null);
    var sku2 = createSku("sku2", "2.50", null);

    // act
    accumulator.addSku(sku1, List.of(createUnit("in_stock"), createUnit("in_stock")));
    accumulator.addSku(sku2, List.of(createUnit("in_stock")));

    // assert
    var totals = accumulator.toTotals();
    assertThat(totals.inventoryValue()).isEqualTo("5.50");
    assertThat(totals.inStockUnits()).isEqualTo(3);
    assertThat(totals.skuCount()).isEqualTo(2);
  }

  private static TcgInventoryItem createSku(
      String skuId, String lastPublishedPrice, String suggestedPrice) {
    return createSku(skuId, lastPublishedPrice, suggestedPrice, "set1", "Set One");
  }

  private static TcgInventoryItem createSku(
      String skuId,
      String lastPublishedPrice,
      String suggestedPrice,
      String setCode,
      String setName) {
    var item = new TcgInventoryItem();
    item.setSkuId(skuId);
    item.setLastPublishedPrice(lastPublishedPrice);
    item.setSuggestedPrice(suggestedPrice);
    item.setSetCode(setCode);
    item.setSetName(setName);
    return item;
  }

  private static TcgInventoryItem createUnit(String status) {
    var item = new TcgInventoryItem();
    item.setStatus(status);
    item.setCreatedAt(Instant.ofEpochSecond(1700000000));
    return item;
  }

  @Test
  void toTopSetsShouldReturnSetsOrderedByInStockUnitsDescending() {
    // arrange
    var accumulator = new ReportAccumulator();
    accumulator.addSku(
        createSku("sku1", "1.00", null, "a25", "Masters 25"),
        List.of(createUnit("in_stock"), createUnit("in_stock"), createUnit("in_stock")));
    accumulator.addSku(
        createSku("sku2", "1.00", null, "cmr", "Commander Legends"),
        List.of(createUnit("in_stock")));
    accumulator.addSku(
        createSku("sku3", "1.00", null, "dom", "Dominaria"),
        List.of(createUnit("in_stock"), createUnit("in_stock")));

    // act
    var topSets = accumulator.toTopSets();

    // assert
    assertThat(topSets).hasSize(3);
    assertThat(topSets.get(0).setCode()).isEqualTo("a25");
    assertThat(topSets.get(0).setName()).isEqualTo("Masters 25");
    assertThat(topSets.get(0).inStockUnits()).isEqualTo(3);
    assertThat(topSets.get(1).setCode()).isEqualTo("dom");
    assertThat(topSets.get(1).inStockUnits()).isEqualTo(2);
    assertThat(topSets.get(2).setCode()).isEqualTo("cmr");
    assertThat(topSets.get(2).inStockUnits()).isEqualTo(1);
  }

  @Test
  void toTopSetsShouldLimitToTenSets() {
    // arrange
    var accumulator = new ReportAccumulator();
    for (int i = 0; i < 12; i++) {
      accumulator.addSku(
          createSku("sku" + i, "1.00", null, "set" + i, "Set " + i),
          List.of(createUnit("in_stock")));
    }

    // act
    var topSets = accumulator.toTopSets();

    // assert
    assertThat(topSets).hasSize(10);
  }

  @Test
  void toTopSetsShouldTieBreakBySetNameAscending() {
    // arrange
    var accumulator = new ReportAccumulator();
    accumulator.addSku(
        createSku("sku1", "1.00", null, "dom", "Dominaria"),
        List.of(createUnit("in_stock"), createUnit("in_stock")));
    accumulator.addSku(
        createSku("sku2", "1.00", null, "a25", "Masters 25"),
        List.of(createUnit("in_stock"), createUnit("in_stock")));
    accumulator.addSku(
        createSku("sku3", "1.00", null, "cmr", "Commander Legends"),
        List.of(createUnit("in_stock"), createUnit("in_stock")));

    // act
    var topSets = accumulator.toTopSets();

    // assert
    assertThat(topSets).hasSize(3);
    assertThat(topSets.get(0).setName()).isEqualTo("Commander Legends");
    assertThat(topSets.get(1).setName()).isEqualTo("Dominaria");
    assertThat(topSets.get(2).setName()).isEqualTo("Masters 25");
  }

  @Test
  void toTopSetsShouldAggregateAcrossMultipleSkusInSameSet() {
    // arrange
    var accumulator = new ReportAccumulator();
    accumulator.addSku(
        createSku("sku1", "1.00", null, "a25", "Masters 25"),
        List.of(createUnit("in_stock"), createUnit("in_stock")));
    accumulator.addSku(
        createSku("sku2", "2.00", null, "a25", "Masters 25"),
        List.of(createUnit("in_stock"), createUnit("in_stock"), createUnit("in_stock")));

    // act
    var topSets = accumulator.toTopSets();

    // assert
    assertThat(topSets).hasSize(1);
    assertThat(topSets.get(0).setCode()).isEqualTo("a25");
    assertThat(topSets.get(0).inStockUnits()).isEqualTo(5);
  }

  @Test
  void toTopSetsShouldExcludeNonInStockUnits() {
    // arrange
    var accumulator = new ReportAccumulator();
    accumulator.addSku(
        createSku("sku1", "1.00", null, "a25", "Masters 25"),
        List.of(
            createUnit("in_stock"),
            createUnit("reserved"),
            createUnit("sold"),
            createUnit("removed")));

    // act
    var topSets = accumulator.toTopSets();

    // assert
    assertThat(topSets).hasSize(1);
    assertThat(topSets.get(0).inStockUnits()).isEqualTo(1);
  }

  @Test
  void toTopSetsShouldReturnEmptyWhenNoInStockUnits() {
    // arrange
    var accumulator = new ReportAccumulator();
    accumulator.addSku(
        createSku("sku1", "1.00", null, "a25", "Masters 25"),
        List.of(createUnit("sold"), createUnit("removed")));

    // act
    var topSets = accumulator.toTopSets();

    // assert
    assertThat(topSets).isEmpty();
  }

  private static TcgInventoryItem createOrder(String status, String totalPrice) {
    var item = new TcgInventoryItem();
    item.setStatus(status);
    item.setTotalPrice(totalPrice);
    return item;
  }
}
