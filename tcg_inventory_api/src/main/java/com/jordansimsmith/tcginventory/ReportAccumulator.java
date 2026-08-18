package com.jordansimsmith.tcginventory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ReportAccumulator {
  private static final int TOP_SETS_LIMIT = 10;
  private static final int TOP_HITS_LIMIT = 10;

  private static final BigDecimal BUCKET_0_50 = new BigDecimal("0.50");
  private static final BigDecimal BUCKET_1 = new BigDecimal("1");
  private static final BigDecimal BUCKET_2 = new BigDecimal("2");
  private static final BigDecimal BUCKET_5 = new BigDecimal("5");
  private static final BigDecimal BUCKET_10 = new BigDecimal("10");

  private static final String[] BUCKET_LABELS = {
    "$0.25-$0.50", "$0.50-$1", "$1-$2", "$2-$5", "$5-$10", "$10+"
  };

  private BigDecimal inventoryValue = BigDecimal.ZERO;
  private int inStockUnits = 0;
  private int skuCount = 0;
  private int reservedUnits = 0;
  private int soldUnits = 0;
  private BigDecimal revenueToDate = BigDecimal.ZERO;
  private int unpricedUnits = 0;

  private final int[] priceBucketCounts = new int[6];
  private final Map<String, SetAccumulator> setMap = new HashMap<>();
  private final List<HitCandidate> hitCandidates = new ArrayList<>();

  public void addSku(TcgInventoryItem sku, List<TcgInventoryItem> units) {
    skuCount++;

    var price = resolvePrice(sku);

    int skuInStockCount = 0;
    for (var unit : units) {
      var status = unit.getStatus();
      if ("removed".equals(status)) {
        continue;
      }
      switch (status) {
        case "in_stock" -> {
          inStockUnits++;
          skuInStockCount++;
          if (price != null) {
            inventoryValue = inventoryValue.add(price);
            priceBucketCounts[bucketIndex(price)]++;
          } else {
            unpricedUnits++;
          }
        }
        case "reserved" -> reservedUnits++;
        case "sold" -> soldUnits++;
        default -> {}
      }
    }

    if (skuInStockCount > 0) {
      setMap
          .computeIfAbsent(sku.getSetCode(), k -> new SetAccumulator(sku.getSetName()))
          .addUnits(skuInStockCount);

      if (price != null) {
        hitCandidates.add(
            new HitCandidate(
                sku.getSkuId(),
                sku.getName(),
                sku.getSetCode(),
                sku.getCollectorNumber(),
                sku.getFinish(),
                sku.getCondition(),
                price,
                skuInStockCount));
      }
    }
  }

  public void addOrder(TcgInventoryItem order) {
    var status = order.getStatus();
    if (!"to_pick".equals(status) && !"fulfilled".equals(status)) {
      return;
    }
    if (order.getTotalPrice() != null) {
      revenueToDate = revenueToDate.add(new BigDecimal(order.getTotalPrice()));
    }
  }

  public ReportPayload.Totals toTotals() {
    return new ReportPayload.Totals(
        inventoryValue.toPlainString(),
        inStockUnits,
        skuCount,
        reservedUnits,
        soldUnits,
        revenueToDate.toPlainString(),
        unpricedUnits);
  }

  public List<ReportPayload.TopSet> toTopSets() {
    return setMap.entrySet().stream()
        .sorted(
            Comparator.<Map.Entry<String, SetAccumulator>>comparingInt(
                    e -> e.getValue().inStockUnits)
                .reversed()
                .thenComparing(e -> e.getValue().setName))
        .limit(TOP_SETS_LIMIT)
        .map(
            e ->
                new ReportPayload.TopSet(
                    e.getKey(), e.getValue().setName, e.getValue().inStockUnits))
        .toList();
  }

  public List<ReportPayload.PriceBucket> toPriceBuckets() {
    var buckets = new ArrayList<ReportPayload.PriceBucket>(BUCKET_LABELS.length);
    for (int i = 0; i < BUCKET_LABELS.length; i++) {
      buckets.add(new ReportPayload.PriceBucket(BUCKET_LABELS[i], priceBucketCounts[i]));
    }
    return buckets;
  }

  public List<ReportPayload.TopHit> toTopHits() {
    return hitCandidates.stream()
        .sorted(
            Comparator.<HitCandidate, BigDecimal>comparing(HitCandidate::price)
                .reversed()
                .thenComparing(HitCandidate::name))
        .limit(TOP_HITS_LIMIT)
        .map(
            h ->
                new ReportPayload.TopHit(
                    h.skuId(),
                    h.name(),
                    h.setCode(),
                    h.collectorNumber(),
                    h.finish(),
                    h.condition(),
                    h.price().toPlainString(),
                    h.inStockUnits()))
        .toList();
  }

  static int bucketIndex(BigDecimal price) {
    if (price.compareTo(BUCKET_0_50) < 0) {
      return 0;
    } else if (price.compareTo(BUCKET_1) < 0) {
      return 1;
    } else if (price.compareTo(BUCKET_2) < 0) {
      return 2;
    } else if (price.compareTo(BUCKET_5) < 0) {
      return 3;
    } else if (price.compareTo(BUCKET_10) < 0) {
      return 4;
    } else {
      return 5;
    }
  }

  static BigDecimal resolvePrice(TcgInventoryItem sku) {
    if (sku.getLastPublishedPrice() != null) {
      return new BigDecimal(sku.getLastPublishedPrice());
    }
    if (sku.getSuggestedPrice() != null) {
      return new BigDecimal(sku.getSuggestedPrice());
    }
    return null;
  }

  private static class SetAccumulator {
    final String setName;
    int inStockUnits;

    SetAccumulator(String setName) {
      this.setName = setName;
    }

    void addUnits(int count) {
      inStockUnits += count;
    }
  }

  private record HitCandidate(
      String skuId,
      String name,
      String setCode,
      String collectorNumber,
      String finish,
      String condition,
      BigDecimal price,
      int inStockUnits) {}
}
