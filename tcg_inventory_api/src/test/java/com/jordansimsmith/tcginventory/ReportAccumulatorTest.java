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
    var item = new TcgInventoryItem();
    item.setSkuId(skuId);
    item.setLastPublishedPrice(lastPublishedPrice);
    item.setSuggestedPrice(suggestedPrice);
    return item;
  }

  private static TcgInventoryItem createUnit(String status) {
    var item = new TcgInventoryItem();
    item.setStatus(status);
    item.setCreatedAt(Instant.ofEpochSecond(1700000000));
    return item;
  }

  private static TcgInventoryItem createOrder(String status, String totalPrice) {
    var item = new TcgInventoryItem();
    item.setStatus(status);
    item.setTotalPrice(totalPrice);
    return item;
  }
}
