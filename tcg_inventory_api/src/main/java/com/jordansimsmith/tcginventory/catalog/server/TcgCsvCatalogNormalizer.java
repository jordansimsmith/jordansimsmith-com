package com.jordansimsmith.tcginventory.catalog.server;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TcgCsvCatalogNormalizer {
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
      TcgCsvCatalogDefinition definition,
      TcgCsvClient.UpdateMarker marker,
      Instant createdAt,
      List<TcgCsvClient.Group> groups,
      List<GroupSource> groupSources) {
    validateGroups(definition.categoryId(), groups);
    var validatedGroups = validateProductsAndFinishes(definition.categoryId(), groupSources);
    var snapshotProducts = normalizeProducts(definition, validatedGroups);
    var snapshotGroups = createSnapshotGroups(groups);

    snapshotGroups.sort(Comparator.comparingInt(TcgCsvCatalogSnapshot.Group::groupId));
    snapshotProducts.sort(Comparator.comparingInt(TcgCsvCatalogSnapshot.Product::productId));
    return new TcgCsvCatalogSnapshot(
        1,
        definition.gameId(),
        "tcgplayer",
        "tcgcsv",
        definition.categoryId(),
        marker.updatedAt().toString(),
        marker.sourceMarker(),
        marker.updatedAt().getEpochSecond(),
        createdAt.getEpochSecond(),
        snapshotGroups,
        snapshotProducts);
  }

  private void validateGroups(int categoryId, List<TcgCsvClient.Group> groups) {
    var groupIds = new HashSet<Integer>();
    for (var group : groups) {
      if (group.categoryId() != categoryId
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
      var setCode = hasText(group.abbreviation()) ? group.abbreviation() : null;
      snapshotGroups.add(new TcgCsvCatalogSnapshot.Group(group.groupId(), setCode, group.name()));
    }
    return snapshotGroups;
  }

  private List<ValidatedGroup> validateProductsAndFinishes(
      int categoryId, List<GroupSource> groupSources) {
    var productIds = new HashSet<Integer>();
    var validatedGroups = new ArrayList<ValidatedGroup>();
    for (var source : groupSources) {
      validatedGroups.add(validateGroupProductsAndFinishes(categoryId, source, productIds));
    }
    return validatedGroups;
  }

  private ValidatedGroup validateGroupProductsAndFinishes(
      int categoryId, GroupSource source, Set<Integer> productIds) {
    var group = source.group();
    var groupProductIds = new HashSet<Integer>();
    for (var product : source.products()) {
      if (product.categoryId() != categoryId
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
      TcgCsvCatalogDefinition definition, List<ValidatedGroup> validatedGroups) {
    var normalizedProducts = new ArrayList<TcgCsvCatalogSnapshot.Product>();

    for (var validatedGroup : validatedGroups) {
      var group = validatedGroup.group();
      for (var product : validatedGroup.products()) {
        var fields = new HashMap<String, String>();
        for (var field : product.extendedData()) {
          fields.put(field.name(), field.value());
        }
        var collectorNumber = fields.get("Number");
        var isSingle =
            hasText(collectorNumber)
                && (hasText(fields.get("Card Type"))
                    || hasText(fields.get("CardType"))
                    || hasText(fields.get("Rarity")));
        if (!isSingle) {
          continue;
        }

        var subtypes =
            validatedGroup.subtypesByProduct().getOrDefault(product.productId(), Set.of());
        var finishes =
            definition.finishes().stream()
                .filter(finish -> subtypes.contains(finish.sourceSubtype()))
                .map(TcgCsvCatalogDefinition.FinishMapping::finishId)
                .toList();
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

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
