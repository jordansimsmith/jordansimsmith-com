package com.jordansimsmith.tcginventory.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

public class TcgCsvCatalogNormalizerTest {
  private static final Instant UPDATED_AT = Instant.parse("2026-10-07T20:06:09Z");
  private static final TcgCsvClient.UpdateMarker MARKER =
      new TcgCsvClient.UpdateMarker("2026-10-07T20:06:09+0000", UPDATED_AT);
  private static final Instant CREATED_AT = Instant.parse("2026-10-08T01:00:00Z");

  private final TcgCsvCatalogNormalizer normalizer = new TcgCsvCatalogNormalizer();

  @Test
  void normalizeShouldRetainSinglesAndMapOnlySupportedFinishes() {
    // arrange
    var group = group(3170, "SWSH12", "Silver Tempest");
    var products =
        List.of(
            product(10, 3170, "Candice", "190/195", "Trainer - Supporter"),
            product(11, 3170, "Lugia VSTAR", "139/195", "Colorless"));
    var prices =
        List.of(
            new TcgCsvClient.Price(10, "Normal"),
            new TcgCsvClient.Price(10, "Reverse Holofoil"),
            new TcgCsvClient.Price(10, "1st Edition"),
            new TcgCsvClient.Price(11, "Holofoil"));

    // act
    var result = normalize(List.of(group), source(group, products, prices));

    // assert
    assertThat(result.products())
        .containsExactly(
            new TcgCsvCatalogSnapshot.Product(
                10, "Candice", 3170, "190/195", List.of("normal", "reverse_holofoil")),
            new TcgCsvCatalogSnapshot.Product(
                11, "Lugia VSTAR", 3170, "139/195", List.of("holofoil")));
  }

  @Test
  void normalizeShouldExcludeProductsWithoutCardMetadata() {
    // arrange
    var group = group(3170, "SWSH12", "Silver Tempest");
    var products =
        List.of(
            product(1, 3170, "Silver Tempest Booster Box", null, null),
            product(2, 3170, "Code Card - Silver Tempest", null, null, "Rarity", "Code Card"),
            product(5, 3170, "Silver Tempest Sleeves", null, null),
            product(6, 3170, "Jumbo Ice Cream", "084/198", "Trainer - Item"),
            product(7, 3170, "VSTAR Token", null, null));
    var prices =
        products.stream()
            .map(product -> new TcgCsvClient.Price(product.productId(), "Normal"))
            .toList();

    // act
    var result = normalize(List.of(group), source(group, products, prices));

    // assert
    assertThat(result.products())
        .containsExactly(
            new TcgCsvCatalogSnapshot.Product(
                6, "Jumbo Ice Cream", 3170, "084/198", List.of("normal")));
  }

  @Test
  void normalizeShouldKeepDistinctProductIdsAndFullTreatmentNames() {
    // arrange
    var group = group(3170, "SWSH12", "Silver Tempest");
    var products =
        List.of(
            product(20, 3170, "Pikachu", "055/195", "Lightning"),
            product(21, 3170, "Pikachu (Cosmos Holo)", "055/195", "Lightning"));
    var prices =
        List.of(new TcgCsvClient.Price(20, "Normal"), new TcgCsvClient.Price(21, "Holofoil"));

    // act
    var result = normalize(List.of(group), source(group, products, prices));

    // assert
    assertThat(result.products())
        .extracting(TcgCsvCatalogSnapshot.Product::productId)
        .containsExactly(20, 21);
    assertThat(result.products())
        .extracting(TcgCsvCatalogSnapshot.Product::name)
        .containsExactly("Pikachu", "Pikachu (Cosmos Holo)");
  }

  @Test
  void normalizeShouldExcludeGroupsWithMissingEmptyOrBlankSetCodes() {
    // arrange
    var groups =
        List.of(
            group(23330, "", "My First Battle"),
            group(999, null, "New Set"),
            group(1000, "  ", "Another Set"));
    var sources =
        groups.stream()
            .map(
                group ->
                    source(
                        group,
                        List.of(
                            product(
                                group.groupId(), group.groupId(), "Pikachu", "001", "Lightning")),
                        List.of(new TcgCsvClient.Price(group.groupId(), "Normal"))))
            .toList();

    // act
    var result = normalizer.normalize(MARKER, CREATED_AT, groups, sources);

    // assert
    assertThat(result.groups()).isEmpty();
    assertThat(result.products()).isEmpty();
  }

  @Test
  void normalizeShouldRetainOnlyNumberedReplicasAndPromos() {
    // arrange
    var worldChampionships = group(2282, "WCD", "World Championship Decks");
    var promos = group(1418, "WP", "WoTC Promo");
    var replica = product(31, 2282, "Pikachu - 2004 (Test Player)", null, "Lightning");
    var numberedReplica =
        product(32, 2282, "Pikachu - 2004 (Second Player)", "12/100", "Lightning");
    var promo = product(696103, 1418, "Pikachu (Corocoro Grey Star)", null, "Lightning");

    // act
    var result =
        normalize(
            List.of(promos, worldChampionships),
            source(
                worldChampionships,
                List.of(replica, numberedReplica),
                List.of(
                    new TcgCsvClient.Price(31, "Normal"), new TcgCsvClient.Price(32, "Normal"))),
            source(promos, List.of(promo), List.of(new TcgCsvClient.Price(696103, "Normal"))));

    // assert
    assertThat(result.products())
        .containsExactly(
            new TcgCsvCatalogSnapshot.Product(
                32, "Pikachu - 2004 (Second Player)", 2282, "12/100", List.of("normal")));
  }

  @Test
  void normalizeShouldExcludeMissingEmptyAndBlankCollectorNumbersIncludingEnergy() {
    // arrange
    var group = group(3170, "SWSH12", "Silver Tempest");
    var products =
        List.of(
            product(33, 3170, "Pikachu", null, "Lightning"),
            product(34, 3170, "Fire Energy", "", "Energy"),
            product(35, 3170, "Grass Energy", "  ", "Basic Grass Energy"),
            product(36, 3170, "Basic Energy", null, "Basic Energy"),
            product(37, 3170, "Numbered Energy", "001", "Energy"));
    var prices =
        products.stream()
            .map(product -> new TcgCsvClient.Price(product.productId(), "Normal"))
            .toList();

    // act
    var result = normalize(List.of(group), source(group, products, prices));

    // assert
    assertThat(result.products())
        .containsExactly(
            new TcgCsvCatalogSnapshot.Product(
                37, "Numbered Energy", 3170, "001", List.of("normal")));
  }

  @Test
  void normalizeShouldRejectMalformedExtendedDataAfterExcludingAnUnnumberedProduct() {
    // arrange
    var group = group(3170, "SWSH12", "Silver Tempest");
    var malformed = new TcgCsvClient.Product(35, 3, 3170, "Later Product", null);
    var source =
        source(
            group,
            List.of(product(34, 3170, "Pikachu", null, "Lightning"), malformed),
            List.of(new TcgCsvClient.Price(34, "Normal"), new TcgCsvClient.Price(35, "Normal")));

    // act / assert
    assertThatThrownBy(() -> normalize(List.of(group), source))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TCGCSV product has no extended data list: 35");
  }

  @Test
  void normalizeShouldIncludeProductsRegardlessOfNameLanguageGroupOrRarity() {
    // arrange
    var group = group(1528, "JUMBO", "Jumbo Cards");
    var products =
        List.of(
            product(36, 1528, "Aaron's Collection", "88/111", "Supporter"),
            product(37, 1528, "Box of Disaster", "154/196", "Item"),
            product(38, 1528, "Zacian V-UNION [Set of 4]", "SWSH163-166", "Metal"),
            product(39, 1528, "Pikachu (Japanese)", "001", "Lightning"),
            product(40, 1528, "Mabosstiff ex - Jumbo Card", "086", "Darkness"),
            product(41, 1528, "Code Card - Test", "002", "Trainer", "Rarity", "Code Card"),
            product(42, 1528, "VSTAR Token", "003", "Trainer"));
    var prices =
        products.stream()
            .map(product -> new TcgCsvClient.Price(product.productId(), "Normal"))
            .toList();

    // act
    var result = normalize(List.of(group), source(group, products, prices));

    // assert
    assertThat(result.products())
        .extracting(TcgCsvCatalogSnapshot.Product::productId)
        .containsExactly(36, 37, 38, 39, 40, 41, 42);
  }

  @Test
  void normalizeShouldExcludeProductsWithOnlyEditionSubtypeRows() {
    // arrange
    var group = group(3170, "SWSH12", "Silver Tempest");
    var card = product(40, 3170, "Pikachu", "055/195", "Lightning");
    var source = source(group, List.of(card), List.of(new TcgCsvClient.Price(40, "Unlimited")));

    // act
    var result = normalize(List.of(group), source);

    // assert
    assertThat(result.products()).isEmpty();
  }

  @Test
  void normalizeShouldIgnoreUnsupportedSubtypesAndRetainSupportedFinishes() {
    // arrange
    var group = group(3170, "SWSH12", "Silver Tempest");
    var products =
        List.of(
            product(50, 3170, "Pikachu", "055/195", "Lightning"),
            product(51, 3170, "Candice", "190/195", "Supporter"));
    var prices =
        List.of(
            new TcgCsvClient.Price(50, "Etched"),
            new TcgCsvClient.Price(51, "New Finish"),
            new TcgCsvClient.Price(51, "Holofoil"));

    // act
    var result = normalize(List.of(group), source(group, products, prices));

    // assert
    assertThat(result.products())
        .containsExactly(
            new TcgCsvCatalogSnapshot.Product(51, "Candice", 3170, "190/195", List.of("holofoil")));
  }

  @Test
  void normalizeShouldRetainCardsWithAnyNonemptyCardType() {
    // arrange
    var group = group(3170, "SWSH12", "Silver Tempest");
    var card = product(60, 3170, "New Card", "001", "New Source Type");
    var source = source(group, List.of(card), List.of(new TcgCsvClient.Price(60, "Normal")));

    // act
    var result = normalize(List.of(group), source);

    // assert
    assertThat(result.products())
        .containsExactly(
            new TcgCsvCatalogSnapshot.Product(60, "New Card", 3170, "001", List.of("normal")));
  }

  @Test
  void normalizeShouldExcludeMissingEmptyAndBlankCardTypesDespiteOtherMetadata() {
    // arrange
    var group = group(3170, "SWSH12", "Silver Tempest");
    var products =
        List.of(
            product(61, 3170, "Missing Type", "001", null, "Rarity", "Common"),
            product(63, 3170, "Empty Type", "002", "", "Rarity", "Rare"),
            product(64, 3170, "Blank Type", "003", "  ", "HP", "100"));
    var prices =
        products.stream()
            .map(product -> new TcgCsvClient.Price(product.productId(), "Normal"))
            .toList();

    // act
    var result = normalize(List.of(group), source(group, products, prices));

    // assert
    assertThat(result.products()).isEmpty();
  }

  @Test
  void normalizeShouldValidatePriceJoinsEvenForExcludedGroups() {
    // arrange
    var group = group(23330, "", "My First Battle");
    var card = product(65, 23330, "Pikachu", "001", "Lightning");
    var source = source(group, List.of(card), List.of(new TcgCsvClient.Price(66, "Normal")));

    // act / assert
    assertThatThrownBy(() -> normalize(List.of(group), source))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("orphan TCGCSV price row");
  }

  @Test
  void normalizeShouldRejectMissingEmptyOrBlankSubtypes() {
    // arrange
    var group = group(3170, "SWSH12", "Silver Tempest");
    var card = product(65, 3170, "Pikachu", "001", "Lightning");
    var subtypes = new String[] {null, "", "  "};

    // act / assert
    for (var subtype : subtypes) {
      var source = source(group, List.of(card), List.of(new TcgCsvClient.Price(65, subtype)));
      assertThatThrownBy(() -> normalize(List.of(group), source))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("TCGCSV price row has no subtype for product 65");
    }
  }

  @Test
  void normalizeShouldExplicitlyExcludeCardsWithoutSourceFinishRows() {
    // arrange
    var group = group(3170, "SWSH12", "Silver Tempest");
    var card = product(62, 3170, "Pikachu", "055/195", "Lightning");
    var source = source(group, List.of(card), List.of());

    // act
    var result = normalize(List.of(group), source);

    // assert
    assertThat(result.products()).isEmpty();
  }

  @Test
  void normalizeShouldRejectDuplicateProductsOrOrphanPriceRows() {
    // arrange
    var group = group(3170, "SWSH12", "Silver Tempest");
    var card = product(70, 3170, "Pikachu", "055/195", "Lightning");
    var duplicate = source(group, List.of(card, card), List.of());
    var orphan = source(group, List.of(card), List.of(new TcgCsvClient.Price(71, "Normal")));

    // act / assert
    assertThatThrownBy(() -> normalize(List.of(group), duplicate))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate TCGCSV product identity");
    assertThatThrownBy(() -> normalize(List.of(group), orphan))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("orphan TCGCSV price row");
  }

  @Test
  void normalizeShouldRejectDuplicateProductSubtypesAndInvalidMembership() {
    // arrange
    var group = group(3170, "SWSH12", "Silver Tempest");
    var card = product(71, 3170, "Pikachu", "055/195", "Lightning");
    var duplicateSubtype =
        source(
            group,
            List.of(card),
            List.of(new TcgCsvClient.Price(71, "Normal"), new TcgCsvClient.Price(71, "Normal")));
    var wrongCategory =
        new TcgCsvClient.Product(
            72,
            4,
            3170,
            "Pikachu",
            List.of(new TcgCsvClient.ExtendedData("Card Type", "Lightning")));
    var wrongGroup = product(73, 999, "Pikachu", "055/195", "Lightning");

    // act / assert
    assertThatThrownBy(() -> normalize(List.of(group), duplicateSubtype))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate TCGCSV product subtype row");
    assertThatThrownBy(
            () ->
                normalize(
                    List.of(group),
                    source(
                        group,
                        List.of(wrongCategory),
                        List.of(new TcgCsvClient.Price(72, "Normal")))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid or duplicate TCGCSV product identity");
    assertThatThrownBy(
            () ->
                normalize(
                    List.of(group),
                    source(
                        group, List.of(wrongGroup), List.of(new TcgCsvClient.Price(73, "Normal")))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid or duplicate TCGCSV product identity");
  }

  @Test
  void normalizeShouldRejectPricesFromAnotherGroup() {
    // arrange
    var firstGroup = group(3170, "SWSH12", "Silver Tempest");
    var secondGroup = group(1418, "WP", "WoTC Promo");
    var firstSource =
        source(
            firstGroup,
            List.of(product(74, 3170, "Pikachu", "055/195", "Lightning")),
            List.of(new TcgCsvClient.Price(74, "Normal")));
    var secondSource =
        source(secondGroup, List.of(), List.of(new TcgCsvClient.Price(74, "Normal")));

    // act / assert
    assertThatThrownBy(() -> normalize(List.of(firstGroup, secondGroup), firstSource, secondSource))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("orphan TCGCSV price row");
  }

  @Test
  void normalizeShouldRejectDuplicateProductIdsAcrossGroups() {
    // arrange
    var firstGroup = group(3170, "SWSH12", "Silver Tempest");
    var secondGroup = group(1418, "WP", "WoTC Promo");
    var firstSource =
        source(
            firstGroup, List.of(product(75, 3170, "Pikachu", "055/195", "Lightning")), List.of());
    var secondSource =
        source(secondGroup, List.of(product(75, 1418, "Pikachu", "001", "Lightning")), List.of());

    // act / assert
    assertThatThrownBy(() -> normalize(List.of(firstGroup, secondGroup), firstSource, secondSource))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate TCGCSV product identity");
  }

  @Test
  void normalizeShouldProduceIdenticalBytesWhenInputCollectionsAreReordered() throws Exception {
    // arrange
    var firstGroup = group(3170, "SWSH12", "Silver Tempest");
    var secondGroup = group(1418, "WP", "WoTC Promo");
    var firstCard = product(77, 3170, "Pikachu", "055/195", "Lightning");
    var secondCard = product(76, 3170, "Candice", "190/195", "Supporter");
    var prices =
        List.of(
            new TcgCsvClient.Price(77, "Reverse Holofoil"),
            new TcgCsvClient.Price(77, "Normal"),
            new TcgCsvClient.Price(76, "Holofoil"));
    var secondSource =
        source(
            secondGroup,
            List.of(product(78, 1418, "Pikachu", "001", "Lightning")),
            List.of(new TcgCsvClient.Price(78, "Normal")));
    var codec = new TcgCsvCatalogArtifactCodec(new ObjectMapper());

    // act
    var first =
        normalize(
            List.of(firstGroup, secondGroup),
            source(firstGroup, List.of(firstCard, secondCard), prices),
            secondSource);
    var reordered =
        normalize(
            List.of(secondGroup, firstGroup),
            secondSource,
            source(firstGroup, List.of(secondCard, firstCard), prices.reversed()));

    // assert
    assertThat(first.groups())
        .extracting(TcgCsvCatalogSnapshot.Group::groupId)
        .containsExactly(1418, 3170);
    assertThat(first.products())
        .extracting(TcgCsvCatalogSnapshot.Product::productId)
        .containsExactly(76, 77, 78);
    assertThat(first.products().get(1).availableFinishes())
        .containsExactly("normal", "reverse_holofoil");
    assertThat(codec.encodeJson(first)).isEqualTo(codec.encodeJson(reordered));
    assertThat(codec.encodeGzip(first)).isEqualTo(codec.encodeGzip(reordered));
  }

  @Test
  void normalizeShouldRejectConflictingProductMetadata() {
    // arrange
    var group = group(3170, "SWSH12", "Silver Tempest");
    var card = product(79, 3170, "Pikachu", "055/195", "Lightning", "Card Type", "Water");
    var source = source(group, List.of(card), List.of(new TcgCsvClient.Price(79, "Normal")));

    // act / assert
    assertThatThrownBy(() -> normalize(List.of(group), source))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("conflicting Card Type metadata for product 79");
  }

  @Test
  void normalizeShouldRejectDuplicateGroupsAndWrongCategories() {
    // arrange
    var group = group(3170, "SWSH12", "Silver Tempest");
    var wrongCategory = new TcgCsvClient.Group(3170, 4, "Silver Tempest", "SWSH12");

    // act / assert
    assertThatThrownBy(() -> normalize(List.of(group, group)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid or duplicate TCGCSV group identity");
    assertThatThrownBy(() -> normalize(List.of(wrongCategory)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid or duplicate TCGCSV group identity");
  }

  @Test
  void encodeShouldProduceDeterministicMinifiedJsonAndGzip() throws Exception {
    // arrange
    var group = group(3170, "SWSH12", "Silver Tempest");
    var source =
        source(
            group,
            List.of(product(80, 3170, "Pikachu", "055/195", "Lightning")),
            List.of(new TcgCsvClient.Price(80, "Normal")));
    var snapshot = normalize(List.of(group), source);
    var codec =
        new TcgCsvCatalogArtifactCodec(
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT));

    // act
    var json = codec.encodeJson(snapshot);
    var gzip = codec.encodeGzip(snapshot);
    var repeatedJson = codec.encodeJson(snapshot);
    var repeatedGzip = codec.encodeGzip(snapshot);
    var decompressed = new GZIPInputStream(new ByteArrayInputStream(gzip)).readAllBytes();

    // assert
    assertThat(json).isEqualTo(repeatedJson);
    assertThat(gzip).isEqualTo(repeatedGzip);
    assertThat(decompressed).isEqualTo(json);
    var jsonText = new String(json);
    assertThat(jsonText)
        .startsWith("{\"schema_version\":1,\"game\":\"pokemon\",\"external_source\":\"tcgplayer\"")
        .contains(
            "\"groups\":[{\"group_id\":3170,\"set_code\":\"SWSH12\",\"set_name\":\"Silver"
                + " Tempest\"}]",
            "\"products\":[{\"product_id\":80,\"name\":\"Pikachu\",\"group_id\":3170,\"collector_number\":\"055/195\",\"available_finishes\":[\"normal\"]}]")
        .doesNotContain("\n", "marketPrice", "CardText", "imageUrl");
  }

  private TcgCsvCatalogSnapshot normalize(
      List<TcgCsvClient.Group> groups, TcgCsvCatalogNormalizer.GroupSource... sources) {
    return normalizer.normalize(MARKER, CREATED_AT, groups, List.of(sources));
  }

  private static TcgCsvClient.Group group(int groupId, String abbreviation, String name) {
    return new TcgCsvClient.Group(groupId, 3, name, abbreviation);
  }

  private static TcgCsvClient.Product product(
      int productId, int groupId, String name, String number, String cardType) {
    return product(productId, groupId, name, number, cardType, null, null);
  }

  private static TcgCsvClient.Product product(
      int productId,
      int groupId,
      String name,
      String number,
      String cardType,
      String additionalName,
      String additionalValue) {
    var extendedData = new ArrayList<TcgCsvClient.ExtendedData>();
    if (number != null) {
      extendedData.add(new TcgCsvClient.ExtendedData("Number", number));
    }
    if (cardType != null) {
      extendedData.add(new TcgCsvClient.ExtendedData("Card Type", cardType));
    }
    if (additionalName != null) {
      extendedData.add(new TcgCsvClient.ExtendedData(additionalName, additionalValue));
    }
    return new TcgCsvClient.Product(productId, 3, groupId, name, extendedData);
  }

  private static TcgCsvCatalogNormalizer.GroupSource source(
      TcgCsvClient.Group group,
      List<TcgCsvClient.Product> products,
      List<TcgCsvClient.Price> prices) {
    return new TcgCsvCatalogNormalizer.GroupSource(group, products, prices);
  }
}
