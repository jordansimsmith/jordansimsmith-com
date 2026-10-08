package com.jordansimsmith.tcginventory.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jordansimsmith.tcginventory.games.Games;
import com.jordansimsmith.tcginventory.games.Games.Game;
import com.jordansimsmith.time.FakeClock;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class TcgPlayerCatalogTest {
  private static final Instant START = Instant.parse("2026-10-08T00:00:00Z");

  @Test
  void constructionAndImageLookupShouldNotLoadCatalog() {
    // arrange
    var fixture = new Fixture("pokemon", snapshot("pokemon", "snapshot-1", "Pikachu", 123));

    // act
    var catalog = fixture.catalog();
    var imageUrls = catalog.getImageUrls("123");

    // assert
    assertThat(fixture.repository.latestSnapshotRequests).isZero();
    assertThat(imageUrls.small())
        .isEqualTo("https://tcgplayer-cdn.tcgplayer.com/product/123_200w.jpg");
    assertThat(imageUrls.normal())
        .isEqualTo("https://tcgplayer-cdn.tcgplayer.com/product/123_in_1000x1000.jpg");
  }

  @Test
  void imageLookupShouldPassThroughOpaqueIds() {
    // arrange
    var catalog =
        new Fixture("pokemon", snapshot("pokemon", "snapshot-1", "Pikachu", 123)).catalog();

    // act
    var imageUrls = catalog.getImageUrls("product-alpha");

    // assert
    assertThat(imageUrls.small())
        .isEqualTo("https://tcgplayer-cdn.tcgplayer.com/product/product-alpha_200w.jpg");
  }

  @Test
  void getCardShouldLoadAndMapSnapshotProduct() {
    // arrange
    var fixture = new Fixture("pokemon", snapshot("pokemon", "snapshot-1", "Pikachu", 123));

    // act
    var card = fixture.catalog().getCard("123");

    // assert
    assertThat(card.game()).isEqualTo("pokemon");
    assertThat(card.externalId()).isEqualTo("123");
    assertThat(card.name()).isEqualTo("Pikachu");
    assertThat(card.setCode()).isEqualTo("sv1");
    assertThat(card.setName()).isEqualTo("Scarlet & Violet");
    assertThat(card.collectorNumber()).isEqualTo("25");
    assertThat(card.availableFinishes()).containsExactly("normal", "holofoil");
    assertThat(card.imageUrls().small())
        .isEqualTo("https://tcgplayer-cdn.tcgplayer.com/product/123_200w.jpg");
    assertThat(fixture.repository.requestedGames).containsExactly("pokemon");
  }

  @Test
  void getCardShouldTreatIdsAsOpaqueAndReturnNotFoundForMissingProducts() {
    // arrange
    var fixture = new Fixture("pokemon", snapshot("pokemon", "snapshot-1", "Pikachu", 123));
    var catalog = fixture.catalog();

    // act / assert
    assertThatThrownBy(() -> catalog.getCard("pokemon-123"))
        .isInstanceOf(CatalogException.NotFound.class);
    assertThatThrownBy(() -> catalog.getCard("999")).isInstanceOf(CatalogException.NotFound.class);
    assertThat(fixture.repository.latestSnapshotRequests).isEqualTo(1);
  }

  @Test
  void findCardsShouldDeduplicateOpaqueIdsAndOmitMissingProducts() {
    // arrange
    var fixture = new Fixture("pokemon", snapshot("pokemon", "snapshot-1", "Pikachu", 123));
    var catalog = fixture.catalog();

    // act
    var cards = catalog.findCards(List.of("product-alpha", "123", "123", "999"));

    // assert
    assertThat(cards).containsOnlyKeys("123");
    assertThat(fixture.repository.latestSnapshotRequests).isEqualTo(1);
  }

  @Test
  void emptyBatchShouldNotLoadCatalog() {
    // arrange
    var fixture = new Fixture("pokemon", snapshot("pokemon", "snapshot-1", "Pikachu", 123));

    // act
    var cards = fixture.catalog().findCards(List.of());

    // assert
    assertThat(cards).isEmpty();
    assertThat(fixture.repository.latestSnapshotRequests).isZero();
  }

  @Test
  void searchAndAlternativesShouldRemainUnavailable() {
    // arrange
    var catalog =
        new Fixture("pokemon", snapshot("pokemon", "snapshot-1", "Pikachu", 123)).catalog();

    // act / assert
    assertThatThrownBy(() -> catalog.search("Pikachu", "normal", null))
        .isInstanceOf(CatalogException.BadRequest.class)
        .hasMessage("catalog review is unavailable for game: pokemon");
    assertThatThrownBy(() -> catalog.findAlternatives("123", "normal", null))
        .isInstanceOf(CatalogException.BadRequest.class)
        .hasMessage("catalog review is unavailable for game: pokemon");
  }

  @Test
  void cachedSnapshotShouldBeReusedUntilFiveMinuteCheckBoundary() {
    // arrange
    var fixture = new Fixture("pokemon", snapshot("pokemon", "snapshot-1", "Pikachu", 123));
    var catalog = fixture.catalog();
    catalog.getCard("123");

    // act
    fixture.clock.setTime(START.plus(Duration.ofMinutes(4)).plusSeconds(59));
    catalog.getCard("123");
    fixture.clock.setTime(START.plus(Duration.ofMinutes(5)));
    catalog.getCard("123");

    // assert
    assertThat(fixture.repository.latestSnapshotRequests).isEqualTo(2);
    assertThat(fixture.store.snapshotLoads).isEqualTo(1);
  }

  @Test
  void changedSnapshotShouldReplaceCachedSnapshot() {
    // arrange
    var first = snapshot("pokemon", "snapshot-1", "Pikachu", 123);
    var second = snapshot("pokemon", "snapshot-2", "Pikachu (Master Ball)", 123);
    var fixture = new Fixture("pokemon", first);
    var catalog = fixture.catalog();
    assertThat(catalog.getCard("123").name()).isEqualTo("Pikachu");
    fixture.repository.latestSnapshot = item("pokemon", "snapshot-2");
    fixture.store.snapshotsById.put("snapshot-2", second);
    fixture.clock.setTime(START.plus(Duration.ofMinutes(5)));

    // act
    var replacement = catalog.getCard("123");

    // assert
    assertThat(replacement.name()).isEqualTo("Pikachu (Master Ball)");
    assertThat(fixture.store.snapshotLoads).isEqualTo(2);
  }

  @Test
  void failedReplacementShouldPreserveCachedSnapshotAndRetryDiscovery() {
    // arrange
    var fixture = new Fixture("pokemon", snapshot("pokemon", "snapshot-1", "Pikachu", 123));
    var catalog = fixture.catalog();
    var original = catalog.getCard("123");
    fixture.repository.latestSnapshot = item("pokemon", "snapshot-2");
    fixture.store.failure = new IOException("broken artifact");
    fixture.clock.setTime(START.plus(Duration.ofMinutes(5)));

    // act / assert
    assertThatThrownBy(() -> catalog.getCard("123"))
        .isInstanceOf(CatalogException.Unavailable.class);
    fixture.store.failure = null;
    fixture.repository.latestSnapshot = item("pokemon", "snapshot-1");
    assertThat(catalog.getCard("123")).isEqualTo(original);
    fixture.repository.latestSnapshot = item("pokemon", "snapshot-2");
    fixture.store.snapshotsById.put(
        "snapshot-2", snapshot("pokemon", "snapshot-2", "New Pikachu", 123));
    fixture.clock.setTime(START.plus(Duration.ofMinutes(10)));
    assertThat(catalog.getCard("123").name()).isEqualTo("New Pikachu");
    assertThat(fixture.repository.latestSnapshotRequests).isEqualTo(4);
  }

  @Test
  void catalogsForGamesShouldKeepOverlappingProductIdsIndependent() {
    // arrange
    var pokemonFixture = new Fixture("pokemon", snapshot("pokemon", "snapshot-1", "Pikachu", 123));
    var otherFixture =
        new Fixture("other", snapshot("other", "snapshot-1", "Other game card", 123));
    var pokemonCatalog = pokemonFixture.catalog();
    var otherCatalog = otherFixture.catalog();
    var catalogs = new Catalogs(Map.of("pokemon", pokemonCatalog, "other", otherCatalog));
    var otherGame =
        new Game("other", "Other game", "tcgplayer", false, false, List.of(), List.of());

    // act
    var pokemonCard = catalogs.forGame(Games.POKEMON_ENGLISH).getCard("123");
    var otherCard = catalogs.forGame(otherGame).getCard("123");

    // assert
    assertThat(pokemonCard.game()).isEqualTo("pokemon");
    assertThat(pokemonCard.name()).isEqualTo("Pikachu");
    assertThat(otherCard.game()).isEqualTo("other");
    assertThat(otherCard.name()).isEqualTo("Other game card");
    assertThat(pokemonFixture.repository.requestedGames).containsExactly("pokemon");
    assertThat(otherFixture.repository.requestedGames).containsExactly("other");
  }

  @Test
  void missingInitialPublicationShouldBeUnavailable() {
    // arrange
    var fixture = new Fixture("pokemon", null);

    // act / assert
    assertThatThrownBy(() -> fixture.catalog().getCard("123"))
        .isInstanceOf(CatalogException.Unavailable.class)
        .hasMessageContaining("not been published");
  }

  private static TcgCsvCatalogSnapshot snapshot(
      String game, String snapshotId, String name, int productId) {
    return new TcgCsvCatalogSnapshot(
        1,
        game,
        "tcgplayer",
        "tcgcsv",
        3,
        snapshotId,
        "source marker",
        1,
        2,
        List.of(new TcgCsvCatalogSnapshot.Group(10, "sv1", "Scarlet & Violet")),
        List.of(
            new TcgCsvCatalogSnapshot.Product(
                productId, name, 10, "25", List.of("normal", "holofoil"))));
  }

  private static CatalogSnapshotItem item(String game, String snapshotId) {
    return CatalogSnapshotItem.create(
        game, snapshotId, START, START, "catalogs/%s.json.gz".formatted(snapshotId), "checksum");
  }

  private static class Fixture {
    private final String game;
    private final FakeClock clock = new FakeClock();
    private final FakeCatalogRepository repository;
    private final FakeCatalogSnapshotStore store;

    private Fixture(String game, TcgCsvCatalogSnapshot snapshot) {
      this.game = game;
      clock.setTime(START);
      var snapshotsById = new LinkedHashMap<String, TcgCsvCatalogSnapshot>();
      if (snapshot != null) {
        snapshotsById.put(snapshot.snapshotId(), snapshot);
      }
      repository =
          new FakeCatalogRepository(snapshot == null ? null : item(game, snapshot.snapshotId()));
      store = new FakeCatalogSnapshotStore(snapshotsById);
    }

    private TcgPlayerCatalog catalog() {
      return new TcgPlayerCatalog(game, repository, store, clock);
    }
  }

  private static class FakeCatalogRepository extends CatalogRepository {
    private CatalogSnapshotItem latestSnapshot;
    private final List<String> requestedGames = new ArrayList<>();
    private int latestSnapshotRequests;

    private FakeCatalogRepository(CatalogSnapshotItem latestSnapshot) {
      super(null, null);
      this.latestSnapshot = latestSnapshot;
    }

    @Override
    public CatalogSnapshotItem getLatestSnapshot(String game) {
      latestSnapshotRequests++;
      requestedGames.add(game);
      return latestSnapshot;
    }
  }

  private static class FakeCatalogSnapshotStore extends CatalogSnapshotStore {
    private final Map<String, TcgCsvCatalogSnapshot> snapshotsById;
    private int snapshotLoads;
    private IOException failure;

    private FakeCatalogSnapshotStore(Map<String, TcgCsvCatalogSnapshot> snapshotsById) {
      super(null, null, null, null);
      this.snapshotsById = snapshotsById;
    }

    @Override
    public TcgCsvCatalogSnapshot getSnapshot(CatalogSnapshotItem item) throws IOException {
      snapshotLoads++;
      if (failure != null) {
        throw failure;
      }
      return snapshotsById.get(item.getSnapshotId());
    }
  }
}
