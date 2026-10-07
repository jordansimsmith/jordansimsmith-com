package com.jordansimsmith.tcginventory.catalog;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class TcgCsvCatalogNormalizer {
  private static final int CATEGORY_ID = 3;
  private static final List<String> FINISH_ORDER =
      List.of("Normal", "Holofoil", "Reverse Holofoil");
  private static final Map<String, String> FINISHES =
      Map.of("Normal", "normal", "Holofoil", "holofoil", "Reverse Holofoil", "reverse_holofoil");

  public record GroupSource(
      TcgCsvClient.Group group,
      List<TcgCsvClient.Product> products,
      List<TcgCsvClient.Price> prices) {
    public GroupSource {
      products = List.copyOf(products);
      prices = List.copyOf(prices);
    }
  }

  private record ValidatedGroup(
      TcgCsvClient.Group group,
      List<TcgCsvClient.Product> products,
      Map<Integer, Set<String>> subtypesByProduct) {}

  public TcgCsvCatalogSnapshot normalize(
      TcgCsvClient.UpdateMarker marker,
      Instant createdAt,
      List<TcgCsvClient.Group> groups,
      List<GroupSource> groupSources) {
    validateGroups(groups);
    var validatedGroups = validateProductsAndFinishes(groupSources);
    var snapshotProducts = normalizeProducts(validatedGroups);
    var snapshotGroups = createSnapshotGroups(groups);

    snapshotGroups.sort(Comparator.comparingInt(TcgCsvCatalogSnapshot.Group::groupId));
    snapshotProducts.sort(Comparator.comparingInt(TcgCsvCatalogSnapshot.Product::productId));
    return new TcgCsvCatalogSnapshot(
        1,
        "pokemon",
        "tcgplayer",
        "tcgcsv",
        CATEGORY_ID,
        marker.updatedAt().toString(),
        marker.sourceMarker(),
        marker.updatedAt().getEpochSecond(),
        createdAt.getEpochSecond(),
        snapshotGroups,
        snapshotProducts);
  }

  private void validateGroups(List<TcgCsvClient.Group> groups) {
    var groupIds = new HashSet<Integer>();
    for (var group : groups) {
      if (group.categoryId() != CATEGORY_ID
          || group.groupId() <= 0
          || group.name() == null
          || group.name().isBlank()
          || !groupIds.add(group.groupId())) {
        throw new IllegalArgumentException("invalid or duplicate TCGCSV group identity");
      }
    }
  }

  private List<TcgCsvCatalogSnapshot.Group> createSnapshotGroups(List<TcgCsvClient.Group> groups) {
    var snapshotGroups = new ArrayList<TcgCsvCatalogSnapshot.Group>();
    for (var group : groups) {
      var setCode = group.abbreviation();
      if (setCode != null && !setCode.isBlank()) {
        snapshotGroups.add(new TcgCsvCatalogSnapshot.Group(group.groupId(), setCode, group.name()));
      }
    }
    return snapshotGroups;
  }

  private List<ValidatedGroup> validateProductsAndFinishes(List<GroupSource> groupSources) {
    var productIds = new HashSet<Integer>();
    var validatedGroups = new ArrayList<ValidatedGroup>();
    for (var source : groupSources) {
      validatedGroups.add(validateGroupProductsAndFinishes(source, productIds));
    }
    return validatedGroups;
  }

  private ValidatedGroup validateGroupProductsAndFinishes(
      GroupSource source, Set<Integer> productIds) {
    var group = source.group();
    var groupProductIds = new HashSet<Integer>();
    for (var product : source.products()) {
      if (product.categoryId() != CATEGORY_ID
          || product.groupId() != group.groupId()
          || product.productId() <= 0
          || product.name() == null
          || product.name().isBlank()
          || !productIds.add(product.productId())) {
        throw new IllegalArgumentException(
            "invalid or duplicate TCGCSV product identity in group " + group.groupId());
      }
      groupProductIds.add(product.productId());
    }

    var subtypesByProduct = new HashMap<Integer, Set<String>>();
    for (var price : source.prices()) {
      if (!groupProductIds.contains(price.productId())) {
        throw new IllegalArgumentException("orphan TCGCSV price row in group " + group.groupId());
      }
      var subtype = price.subTypeName();
      if (subtype == null || subtype.isBlank()) {
        throw new IllegalArgumentException(
            "TCGCSV price row has no subtype for product " + price.productId());
      }
      if (!subtypesByProduct
          .computeIfAbsent(price.productId(), unused -> new HashSet<>())
          .add(subtype)) {
        throw new IllegalArgumentException(
            "duplicate TCGCSV product subtype row for product " + price.productId());
      }
    }

    return new ValidatedGroup(group, source.products(), subtypesByProduct);
  }

  private List<TcgCsvCatalogSnapshot.Product> normalizeProducts(
      List<ValidatedGroup> validatedGroups) {
    var normalizedProducts = new ArrayList<TcgCsvCatalogSnapshot.Product>();
    for (var validatedGroup : validatedGroups) {
      var group = validatedGroup.group();
      for (var product : validatedGroup.products()) {
        if (product.extendedData() == null) {
          throw new IllegalArgumentException(
              "TCGCSV product has no extended data list: " + product.productId());
        }
        var fields = new HashMap<String, String>();
        for (var field : product.extendedData()) {
          if ("Card Type".equals(field.name()) || "Number".equals(field.name())) {
            if (fields.containsKey(field.name())
                && !Objects.equals(fields.get(field.name()), field.value())) {
              throw new IllegalArgumentException(
                  "conflicting %s metadata for product %d"
                      .formatted(field.name(), product.productId()));
            }
            fields.put(field.name(), field.value());
          }
        }
        var cardType = fields.get("Card Type");
        var collectorNumber = fields.get("Number");
        if (cardType == null
            || cardType.isBlank()
            || collectorNumber == null
            || collectorNumber.isBlank()
            || group.abbreviation() == null
            || group.abbreviation().isBlank()) {
          continue;
        }
        var subtypes =
            validatedGroup.subtypesByProduct().getOrDefault(product.productId(), Set.of());
        var finishes = FINISH_ORDER.stream().filter(subtypes::contains).map(FINISHES::get).toList();
        if (finishes.isEmpty()) {
          continue;
        }
        normalizedProducts.add(
            new TcgCsvCatalogSnapshot.Product(
                product.productId(), product.name(), group.groupId(), collectorNumber, finishes));
      }
    }
    return normalizedProducts;
  }
}
