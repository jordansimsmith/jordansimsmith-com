package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

public class ReportAccumulatorTest {
  private static final ZoneId AUCKLAND = ZoneId.of("Pacific/Auckland");
  private static final Instant GENERATION_TIME = Instant.ofEpochSecond(1700000000);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Test
  void addSkuShouldPreferLastPublishedPriceOverSuggestedPrice() {
    // arrange
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
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
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
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
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
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
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
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
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
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
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);

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
  void addOrderShouldExcludeShippingFromRevenue() {
    // arrange
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);

    // act
    accumulator.addOrder(
        createOrder("fulfilled", "19.50", "12.00", Instant.ofEpochSecond(1700000000)));

    // assert
    var totals = accumulator.toTotals();
    assertThat(totals.revenueToDate()).isEqualTo("12.00");
  }

  @Test
  void toTotalsShouldReturnZeroesWhenEmpty() {
    // arrange
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);

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
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
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
    return createSku(skuId, lastPublishedPrice, suggestedPrice, setCode, setName, "Card " + skuId);
  }

  private static TcgInventoryItem createSku(
      String skuId,
      String lastPublishedPrice,
      String suggestedPrice,
      String setCode,
      String setName,
      String name) {
    var item = new TcgInventoryItem();
    item.setSkuId(skuId);
    item.setLastPublishedPrice(lastPublishedPrice);
    item.setSuggestedPrice(suggestedPrice);
    item.setSetCode(setCode);
    item.setSetName(setName);
    item.setName(name);
    item.setCollectorNumber("1");
    item.setFinish("normal");
    item.setCondition("NM");
    return item;
  }

  private static TcgInventoryItem createUnit(String status) {
    var item = new TcgInventoryItem();
    item.setStatus(status);
    item.setCreatedAt(Instant.ofEpochSecond(1700000000));
    return item;
  }

  private static TcgInventoryItem createUnit(String status, Instant createdAt) {
    var item = new TcgInventoryItem();
    item.setStatus(status);
    item.setCreatedAt(createdAt);
    return item;
  }

  private static TcgInventoryItem createUnit(String status, Instant createdAt, Instant updatedAt) {
    var item = new TcgInventoryItem();
    item.setStatus(status);
    item.setCreatedAt(createdAt);
    item.setUpdatedAt(updatedAt);
    return item;
  }

  @Test
  void toTopSetsShouldReturnSetsOrderedByInStockUnitsDescending() {
    // arrange
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
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
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
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
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
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
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
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
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
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
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
    accumulator.addSku(
        createSku("sku1", "1.00", null, "a25", "Masters 25"),
        List.of(createUnit("sold"), createUnit("removed")));

    // act
    var topSets = accumulator.toTopSets();

    // assert
    assertThat(topSets).isEmpty();
  }

  @Test
  void toPriceBucketsShouldPlacePrice025InFirstBucket() {
    // arrange
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
    accumulator.addSku(createSku("sku1", "0.25", null), List.of(createUnit("in_stock")));

    // act
    var buckets = accumulator.toPriceBuckets();

    // assert
    assertThat(buckets).hasSize(6);
    assertThat(buckets.get(0).label()).isEqualTo("$0.25-$0.50");
    assertThat(buckets.get(0).inStockUnits()).isEqualTo(1);
    assertThat(buckets.get(1).inStockUnits()).isEqualTo(0);
  }

  @Test
  void toPriceBucketsShouldPlacePrice050InSecondBucket() {
    // arrange
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
    accumulator.addSku(createSku("sku1", "0.50", null), List.of(createUnit("in_stock")));

    // act
    var buckets = accumulator.toPriceBuckets();

    // assert
    assertThat(buckets.get(0).inStockUnits()).isEqualTo(0);
    assertThat(buckets.get(1).label()).isEqualTo("$0.50-$1");
    assertThat(buckets.get(1).inStockUnits()).isEqualTo(1);
  }

  @Test
  void toPriceBucketsShouldPlacePrice1000InLastBucket() {
    // arrange
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
    accumulator.addSku(createSku("sku1", "10.00", null), List.of(createUnit("in_stock")));

    // act
    var buckets = accumulator.toPriceBuckets();

    // assert
    assertThat(buckets.get(4).inStockUnits()).isEqualTo(0);
    assertThat(buckets.get(5).label()).isEqualTo("$10+");
    assertThat(buckets.get(5).inStockUnits()).isEqualTo(1);
  }

  @Test
  void toPriceBucketsShouldEmitAllBucketsEvenWhenEmpty() {
    // arrange
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);

    // act
    var buckets = accumulator.toPriceBuckets();

    // assert
    assertThat(buckets).hasSize(6);
    assertThat(buckets.get(0).label()).isEqualTo("$0.25-$0.50");
    assertThat(buckets.get(1).label()).isEqualTo("$0.50-$1");
    assertThat(buckets.get(2).label()).isEqualTo("$1-$2");
    assertThat(buckets.get(3).label()).isEqualTo("$2-$5");
    assertThat(buckets.get(4).label()).isEqualTo("$5-$10");
    assertThat(buckets.get(5).label()).isEqualTo("$10+");
    for (var bucket : buckets) {
      assertThat(bucket.inStockUnits()).isEqualTo(0);
    }
  }

  @Test
  void toPriceBucketsShouldExcludeUnpricedUnits() {
    // arrange
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
    accumulator.addSku(
        createSku("sku1", null, null), List.of(createUnit("in_stock"), createUnit("in_stock")));

    // act
    var buckets = accumulator.toPriceBuckets();

    // assert
    for (var bucket : buckets) {
      assertThat(bucket.inStockUnits()).isEqualTo(0);
    }
  }

  @Test
  void toPriceBucketsShouldDistributeAcrossMultipleBuckets() {
    // arrange
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
    accumulator.addSku(createSku("sku1", "0.30", null), List.of(createUnit("in_stock")));
    accumulator.addSku(createSku("sku2", "0.75", null), List.of(createUnit("in_stock")));
    accumulator.addSku(
        createSku("sku3", "1.50", null), List.of(createUnit("in_stock"), createUnit("in_stock")));
    accumulator.addSku(createSku("sku4", "3.00", null), List.of(createUnit("in_stock")));
    accumulator.addSku(createSku("sku5", "7.50", null), List.of(createUnit("in_stock")));
    accumulator.addSku(createSku("sku6", "15.00", null), List.of(createUnit("in_stock")));

    // act
    var buckets = accumulator.toPriceBuckets();

    // assert
    assertThat(buckets.get(0).inStockUnits()).isEqualTo(1);
    assertThat(buckets.get(1).inStockUnits()).isEqualTo(1);
    assertThat(buckets.get(2).inStockUnits()).isEqualTo(2);
    assertThat(buckets.get(3).inStockUnits()).isEqualTo(1);
    assertThat(buckets.get(4).inStockUnits()).isEqualTo(1);
    assertThat(buckets.get(5).inStockUnits()).isEqualTo(1);
  }

  @Test
  void toTopHitsShouldOrderByPriceDescending() {
    // arrange
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
    accumulator.addSku(
        createSku("sku1", "1.00", null, "a25", "Masters 25", "Cheap Card"),
        List.of(createUnit("in_stock")));
    accumulator.addSku(
        createSku("sku2", "10.00", null, "a25", "Masters 25", "Expensive Card"),
        List.of(createUnit("in_stock")));
    accumulator.addSku(
        createSku("sku3", "5.00", null, "a25", "Masters 25", "Mid Card"),
        List.of(createUnit("in_stock")));

    // act
    var topHits = accumulator.toTopHits();

    // assert
    assertThat(topHits).hasSize(3);
    assertThat(topHits.get(0).name()).isEqualTo("Expensive Card");
    assertThat(topHits.get(0).price()).isEqualTo("10.00");
    assertThat(topHits.get(1).name()).isEqualTo("Mid Card");
    assertThat(topHits.get(1).price()).isEqualTo("5.00");
    assertThat(topHits.get(2).name()).isEqualTo("Cheap Card");
    assertThat(topHits.get(2).price()).isEqualTo("1.00");
  }

  @Test
  void toTopHitsShouldTieBreakByNameAscending() {
    // arrange
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
    accumulator.addSku(
        createSku("sku1", "5.00", null, "a25", "Masters 25", "Zebra Card"),
        List.of(createUnit("in_stock")));
    accumulator.addSku(
        createSku("sku2", "5.00", null, "a25", "Masters 25", "Alpha Card"),
        List.of(createUnit("in_stock")));
    accumulator.addSku(
        createSku("sku3", "5.00", null, "a25", "Masters 25", "Middle Card"),
        List.of(createUnit("in_stock")));

    // act
    var topHits = accumulator.toTopHits();

    // assert
    assertThat(topHits).hasSize(3);
    assertThat(topHits.get(0).name()).isEqualTo("Alpha Card");
    assertThat(topHits.get(1).name()).isEqualTo("Middle Card");
    assertThat(topHits.get(2).name()).isEqualTo("Zebra Card");
  }

  @Test
  void toTopHitsShouldLimitToTen() {
    // arrange
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
    for (int i = 0; i < 12; i++) {
      accumulator.addSku(
          createSku("sku" + i, String.valueOf(i + 1) + ".00", null, "a25", "Masters 25"),
          List.of(createUnit("in_stock")));
    }

    // act
    var topHits = accumulator.toTopHits();

    // assert
    assertThat(topHits).hasSize(10);
    assertThat(topHits.get(0).price()).isEqualTo("12.00");
    assertThat(topHits.get(9).price()).isEqualTo("3.00");
  }

  @Test
  void toTopHitsShouldExcludeUnpricedSkus() {
    // arrange
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
    accumulator.addSku(
        createSku("sku1", "5.00", null, "a25", "Masters 25", "Priced Card"),
        List.of(createUnit("in_stock")));
    accumulator.addSku(
        createSku("sku2", null, null, "a25", "Masters 25", "Unpriced Card"),
        List.of(createUnit("in_stock")));

    // act
    var topHits = accumulator.toTopHits();

    // assert
    assertThat(topHits).hasSize(1);
    assertThat(topHits.get(0).name()).isEqualTo("Priced Card");
  }

  @Test
  void toTopHitsShouldExcludeSkusWithZeroInStockUnits() {
    // arrange
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
    accumulator.addSku(
        createSku("sku1", "5.00", null, "a25", "Masters 25", "In Stock Card"),
        List.of(createUnit("in_stock")));
    accumulator.addSku(
        createSku("sku2", "10.00", null, "a25", "Masters 25", "All Reserved Card"),
        List.of(createUnit("reserved"), createUnit("sold")));

    // act
    var topHits = accumulator.toTopHits();

    // assert
    assertThat(topHits).hasSize(1);
    assertThat(topHits.get(0).name()).isEqualTo("In Stock Card");
  }

  @Test
  void toTopHitsShouldIncludeIdentityFieldsAndInStockCount() {
    // arrange
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
    var sku = createSku("my-sku-id", "7.50", null, "mh2", "Modern Horizons 2", "Ragavan");
    sku.setCollectorNumber("138");
    sku.setFinish("foil");
    sku.setCondition("LP");
    accumulator.addSku(sku, List.of(createUnit("in_stock"), createUnit("in_stock")));

    // act
    var topHits = accumulator.toTopHits();

    // assert
    assertThat(topHits).hasSize(1);
    var hit = topHits.get(0);
    assertThat(hit.skuId()).isEqualTo("my-sku-id");
    assertThat(hit.name()).isEqualTo("Ragavan");
    assertThat(hit.setCode()).isEqualTo("mh2");
    assertThat(hit.collectorNumber()).isEqualTo("138");
    assertThat(hit.finish()).isEqualTo("foil");
    assertThat(hit.condition()).isEqualTo("LP");
    assertThat(hit.price()).isEqualTo("7.50");
    assertThat(hit.inStockUnits()).isEqualTo(2);
  }

  @Test
  void toAgingBandsShouldPlaceZeroDaysInFirstBand() {
    // arrange
    var generationTime = GENERATION_TIME;
    var accumulator = new ReportAccumulator(generationTime, OBJECT_MAPPER);
    var unitCreatedAt = generationTime;
    accumulator.addSku(
        createSku("sku1", "1.00", null), List.of(createUnit("in_stock", unitCreatedAt)));

    // act
    var bands = accumulator.toAgingBands();

    // assert
    assertThat(bands.get(0).label()).isEqualTo("0-30 days");
    assertThat(bands.get(0).inStockUnits()).isEqualTo(1);
  }

  @Test
  void toAgingBandsShouldPlaceThirtyDaysInFirstBand() {
    // arrange
    var generationDate = GENERATION_TIME.atZone(AUCKLAND).toLocalDate();
    var unitDate = generationDate.minusDays(30);
    var unitCreatedAt = unitDate.atStartOfDay(AUCKLAND).toInstant();
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
    accumulator.addSku(
        createSku("sku1", "1.00", null), List.of(createUnit("in_stock", unitCreatedAt)));

    // act
    var bands = accumulator.toAgingBands();

    // assert
    assertThat(bands.get(0).inStockUnits()).isEqualTo(1);
    assertThat(bands.get(1).inStockUnits()).isEqualTo(0);
  }

  @Test
  void toAgingBandsShouldPlaceThirtyOneDaysInSecondBand() {
    // arrange
    var generationDate = GENERATION_TIME.atZone(AUCKLAND).toLocalDate();
    var unitDate = generationDate.minusDays(31);
    var unitCreatedAt = unitDate.atStartOfDay(AUCKLAND).toInstant();
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
    accumulator.addSku(
        createSku("sku1", "1.00", null), List.of(createUnit("in_stock", unitCreatedAt)));

    // act
    var bands = accumulator.toAgingBands();

    // assert
    assertThat(bands.get(0).inStockUnits()).isEqualTo(0);
    assertThat(bands.get(1).label()).isEqualTo("31-90 days");
    assertThat(bands.get(1).inStockUnits()).isEqualTo(1);
  }

  @Test
  void toAgingBandsShouldPlaceNinetyDaysInSecondBand() {
    // arrange
    var generationDate = GENERATION_TIME.atZone(AUCKLAND).toLocalDate();
    var unitDate = generationDate.minusDays(90);
    var unitCreatedAt = unitDate.atStartOfDay(AUCKLAND).toInstant();
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
    accumulator.addSku(
        createSku("sku1", "1.00", null), List.of(createUnit("in_stock", unitCreatedAt)));

    // act
    var bands = accumulator.toAgingBands();

    // assert
    assertThat(bands.get(1).inStockUnits()).isEqualTo(1);
    assertThat(bands.get(2).inStockUnits()).isEqualTo(0);
  }

  @Test
  void toAgingBandsShouldPlaceNinetyOneDaysInThirdBand() {
    // arrange
    var generationDate = GENERATION_TIME.atZone(AUCKLAND).toLocalDate();
    var unitDate = generationDate.minusDays(91);
    var unitCreatedAt = unitDate.atStartOfDay(AUCKLAND).toInstant();
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
    accumulator.addSku(
        createSku("sku1", "1.00", null), List.of(createUnit("in_stock", unitCreatedAt)));

    // act
    var bands = accumulator.toAgingBands();

    // assert
    assertThat(bands.get(1).inStockUnits()).isEqualTo(0);
    assertThat(bands.get(2).label()).isEqualTo("91-180 days");
    assertThat(bands.get(2).inStockUnits()).isEqualTo(1);
  }

  @Test
  void toAgingBandsShouldPlaceOneHundredEightyDaysInThirdBand() {
    // arrange
    var generationDate = GENERATION_TIME.atZone(AUCKLAND).toLocalDate();
    var unitDate = generationDate.minusDays(180);
    var unitCreatedAt = unitDate.atStartOfDay(AUCKLAND).toInstant();
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
    accumulator.addSku(
        createSku("sku1", "1.00", null), List.of(createUnit("in_stock", unitCreatedAt)));

    // act
    var bands = accumulator.toAgingBands();

    // assert
    assertThat(bands.get(2).inStockUnits()).isEqualTo(1);
    assertThat(bands.get(3).inStockUnits()).isEqualTo(0);
  }

  @Test
  void toAgingBandsShouldPlaceOneHundredEightyOneDaysInFourthBand() {
    // arrange
    var generationDate = GENERATION_TIME.atZone(AUCKLAND).toLocalDate();
    var unitDate = generationDate.minusDays(181);
    var unitCreatedAt = unitDate.atStartOfDay(AUCKLAND).toInstant();
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
    accumulator.addSku(
        createSku("sku1", "1.00", null), List.of(createUnit("in_stock", unitCreatedAt)));

    // act
    var bands = accumulator.toAgingBands();

    // assert
    assertThat(bands.get(2).inStockUnits()).isEqualTo(0);
    assertThat(bands.get(3).label()).isEqualTo("180+ days");
    assertThat(bands.get(3).inStockUnits()).isEqualTo(1);
  }

  @Test
  void toAgingBandsShouldEmitAllFourBandsWhenEmpty() {
    // arrange
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);

    // act
    var bands = accumulator.toAgingBands();

    // assert
    assertThat(bands).hasSize(4);
    assertThat(bands.get(0).label()).isEqualTo("0-30 days");
    assertThat(bands.get(1).label()).isEqualTo("31-90 days");
    assertThat(bands.get(2).label()).isEqualTo("91-180 days");
    assertThat(bands.get(3).label()).isEqualTo("180+ days");
    for (var band : bands) {
      assertThat(band.inStockUnits()).isEqualTo(0);
    }
  }

  @Test
  void toAgingBandsShouldOnlyCountInStockUnits() {
    // arrange
    var generationDate = GENERATION_TIME.atZone(AUCKLAND).toLocalDate();
    var unitDate = generationDate.minusDays(10);
    var unitCreatedAt = unitDate.atStartOfDay(AUCKLAND).toInstant();
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
    accumulator.addSku(
        createSku("sku1", "1.00", null),
        List.of(
            createUnit("in_stock", unitCreatedAt),
            createUnit("reserved", unitCreatedAt),
            createUnit("sold", unitCreatedAt),
            createUnit("removed", unitCreatedAt)));

    // act
    var bands = accumulator.toAgingBands();

    // assert
    assertThat(bands.get(0).inStockUnits()).isEqualTo(1);
  }

  private static TcgInventoryItem createOrder(String status, String totalPrice) {
    return createOrder(status, totalPrice, Instant.ofEpochSecond(1700000000));
  }

  private static TcgInventoryItem createOrder(String status, String totalPrice, Instant createdAt) {
    return createOrder(status, totalPrice, totalPrice, createdAt);
  }

  private static TcgInventoryItem createOrder(
      String status, String totalPrice, String itemsTotal, Instant createdAt) {
    var item = new TcgInventoryItem();
    item.setStatus(status);
    item.setTotalPrice(totalPrice);
    item.setLines(
        "[{\"sku_id\":\"s\",\"fetchtcg_listing_id\":1,\"quantity\":1,\"price\":\""
            + itemsTotal
            + "\",\"allocated_sequence_numbers\":[]}]");
    item.setCreatedAt(createdAt);
    return item;
  }

  @Test
  void toRevenueByMonthShouldIncludePaidOrdersOnly() {
    // arrange
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
    // 2023-11-14 in Pacific/Auckland
    var nov2023 = Instant.ofEpochSecond(1700000000);

    // act
    accumulator.addOrder(createOrder("to_pick", "10.50", nov2023));
    accumulator.addOrder(createOrder("fulfilled", "5.25", nov2023));
    accumulator.addOrder(createOrder("awaiting_payment", "100.00", nov2023));
    accumulator.addOrder(createOrder("voided", "20.00", nov2023));
    accumulator.addOrder(createOrder("flagged", "30.00", nov2023));

    // assert
    var result = accumulator.toRevenueByMonth();
    assertThat(result).hasSize(1);
    assertThat(result.get(0).month()).isEqualTo("2023-11");
    assertThat(result.get(0).revenue()).isEqualTo("15.75");
    assertThat(result.get(0).orderCount()).isEqualTo(2);
  }

  @Test
  void toRevenueByMonthShouldAggregateOrdersInSameMonth() {
    // arrange
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
    // both in November 2023 NZ time
    var earlyNov = Instant.ofEpochSecond(1698800000);
    var lateNov = Instant.ofEpochSecond(1700000000);

    // act
    accumulator.addOrder(createOrder("fulfilled", "10.00", earlyNov));
    accumulator.addOrder(createOrder("fulfilled", "5.50", lateNov));
    accumulator.addOrder(createOrder("to_pick", "3.25", lateNov));

    // assert
    var result = accumulator.toRevenueByMonth();
    assertThat(result).hasSize(1);
    assertThat(result.get(0).month()).isEqualTo("2023-11");
    assertThat(result.get(0).revenue()).isEqualTo("18.75");
    assertThat(result.get(0).orderCount()).isEqualTo(3);
  }

  @Test
  void toRevenueByMonthShouldSortChronologically() {
    // arrange
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
    // March 2023
    var march = Instant.parse("2023-03-15T00:00:00Z");
    // January 2023
    var january = Instant.parse("2023-01-10T00:00:00Z");
    // November 2023
    var november = Instant.ofEpochSecond(1700000000);

    // act (add in non-chronological order)
    accumulator.addOrder(createOrder("fulfilled", "5.00", march));
    accumulator.addOrder(createOrder("fulfilled", "3.00", january));
    accumulator.addOrder(createOrder("fulfilled", "7.00", november));

    // assert
    var result = accumulator.toRevenueByMonth();
    assertThat(result).hasSize(3);
    assertThat(result.get(0).month()).isEqualTo("2023-01");
    assertThat(result.get(0).revenue()).isEqualTo("3.00");
    assertThat(result.get(1).month()).isEqualTo("2023-03");
    assertThat(result.get(1).revenue()).isEqualTo("5.00");
    assertThat(result.get(2).month()).isEqualTo("2023-11");
    assertThat(result.get(2).revenue()).isEqualTo("7.00");
  }

  @Test
  void toRevenueByMonthShouldReturnEmptyWhenNoOrders() {
    // arrange
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);

    // act
    var result = accumulator.toRevenueByMonth();

    // assert
    assertThat(result).isEmpty();
  }

  @Test
  void toRevenueByMonthShouldSumRevenueCorrectlyAsBigDecimal() {
    // arrange
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
    var instant = Instant.ofEpochSecond(1700000000);

    // act
    accumulator.addOrder(createOrder("fulfilled", "0.10", instant));
    accumulator.addOrder(createOrder("fulfilled", "0.20", instant));

    // assert
    var result = accumulator.toRevenueByMonth();
    assertThat(result).hasSize(1);
    assertThat(result.get(0).revenue()).isEqualTo("0.30");
  }

  @Test
  void toIntakeVsSalesByWeekShouldCountAllNonRemovedStatusesAsAdded() {
    // arrange
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
    // Wednesday 2023-11-15 NZ time -> week start Monday 2023-11-13
    var createdAt = Instant.ofEpochSecond(1700000000);
    var soldAt = Instant.ofEpochSecond(1700100000);
    accumulator.addSku(
        createSku("sku1", "1.00", null),
        List.of(
            createUnit("in_stock", createdAt),
            createUnit("reserved", createdAt),
            createUnit("sold", createdAt, soldAt)));

    // act
    var result = accumulator.toIntakeVsSalesByWeek();

    // assert
    assertThat(result).hasSize(1);
    assertThat(result.get(0).weekStart()).isEqualTo("2023-11-13");
    assertThat(result.get(0).addedUnits()).isEqualTo(3);
  }

  @Test
  void toIntakeVsSalesByWeekShouldExcludeRemovedFromAddedCount() {
    // arrange
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
    var createdAt = Instant.ofEpochSecond(1700000000);
    accumulator.addSku(
        createSku("sku1", "1.00", null),
        List.of(createUnit("in_stock", createdAt), createUnit("removed", createdAt)));

    // act
    var result = accumulator.toIntakeVsSalesByWeek();

    // assert
    assertThat(result).hasSize(1);
    assertThat(result.get(0).addedUnits()).isEqualTo(1);
  }

  @Test
  void toIntakeVsSalesByWeekShouldBucketSoldUnitsByUpdatedAtNotCreatedAt() {
    // arrange
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
    // created Monday 2023-11-06 NZ time
    var createdAt = Instant.parse("2023-11-05T22:00:00Z");
    // sold Wednesday 2023-11-15 NZ time -> week start Monday 2023-11-13
    var soldAt = Instant.ofEpochSecond(1700000000);
    accumulator.addSku(
        createSku("sku1", "1.00", null), List.of(createUnit("sold", createdAt, soldAt)));

    // act
    var result = accumulator.toIntakeVsSalesByWeek();

    // assert
    assertThat(result).hasSize(2);
    // added in the creation week (2023-11-06)
    assertThat(result.get(0).weekStart()).isEqualTo("2023-11-06");
    assertThat(result.get(0).addedUnits()).isEqualTo(1);
    assertThat(result.get(0).soldUnits()).isEqualTo(0);
    // sold in the sell week (2023-11-13)
    assertThat(result.get(1).weekStart()).isEqualTo("2023-11-13");
    assertThat(result.get(1).addedUnits()).isEqualTo(0);
    assertThat(result.get(1).soldUnits()).isEqualTo(1);
  }

  @Test
  void toIntakeVsSalesByWeekShouldAlignWednesdayToPrecedingMonday() {
    // arrange
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
    // 1700000000 is 2023-11-15 in NZ (Wednesday) -> week start 2023-11-13 (Monday)
    var wednesday = Instant.ofEpochSecond(1700000000);
    accumulator.addSku(createSku("sku1", "1.00", null), List.of(createUnit("in_stock", wednesday)));

    // act
    var result = accumulator.toIntakeVsSalesByWeek();

    // assert
    assertThat(result).hasSize(1);
    assertThat(result.get(0).weekStart()).isEqualTo("2023-11-13");
  }

  @Test
  void toIntakeVsSalesByWeekShouldMergeWeeksFromBothMaps() {
    // arrange
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
    // week 1: only adds
    var week1Created = Instant.parse("2023-11-06T00:00:00Z");
    // week 2: only sales (unit was created in week 1 but sold in week 2)
    var week2Sold = Instant.parse("2023-11-13T12:00:00Z");
    accumulator.addSku(
        createSku("sku1", "1.00", null),
        List.of(createUnit("in_stock", week1Created), createUnit("sold", week1Created, week2Sold)));

    // act
    var result = accumulator.toIntakeVsSalesByWeek();

    // assert
    assertThat(result).hasSize(2);
    assertThat(result.get(0).weekStart()).isEqualTo("2023-11-06");
    assertThat(result.get(0).addedUnits()).isEqualTo(2);
    assertThat(result.get(0).soldUnits()).isEqualTo(0);
    assertThat(result.get(1).weekStart()).isEqualTo("2023-11-13");
    assertThat(result.get(1).addedUnits()).isEqualTo(0);
    assertThat(result.get(1).soldUnits()).isEqualTo(1);
  }

  @Test
  void toIntakeVsSalesByWeekShouldSortChronologically() {
    // arrange
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);
    // add units in reverse chronological order
    var laterWeek = Instant.parse("2023-11-13T12:00:00Z");
    var earlierWeek = Instant.parse("2023-11-06T12:00:00Z");
    accumulator.addSku(
        createSku("sku1", "1.00", null),
        List.of(createUnit("in_stock", laterWeek), createUnit("in_stock", earlierWeek)));

    // act
    var result = accumulator.toIntakeVsSalesByWeek();

    // assert
    assertThat(result).hasSize(2);
    assertThat(result.get(0).weekStart()).isEqualTo("2023-11-06");
    assertThat(result.get(1).weekStart()).isEqualTo("2023-11-13");
  }

  @Test
  void toIntakeVsSalesByWeekShouldReturnEmptyWhenNoUnits() {
    // arrange
    var accumulator = new ReportAccumulator(GENERATION_TIME, OBJECT_MAPPER);

    // act
    var result = accumulator.toIntakeVsSalesByWeek();

    // assert
    assertThat(result).isEmpty();
  }
}
