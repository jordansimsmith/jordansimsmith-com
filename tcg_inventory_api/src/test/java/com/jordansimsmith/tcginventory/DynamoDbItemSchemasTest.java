package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jordansimsmith.tcginventory.imports.ImportItem;
import com.jordansimsmith.tcginventory.imports.ImportRowItem;
import com.jordansimsmith.tcginventory.inventory.SequenceCounterItem;
import com.jordansimsmith.tcginventory.inventory.SkuItem;
import com.jordansimsmith.tcginventory.inventory.UnitItem;
import com.jordansimsmith.tcginventory.orders.OrderItem;
import com.jordansimsmith.tcginventory.reports.ReportItem;
import com.jordansimsmith.tcginventory.scans.ScanItem;
import com.jordansimsmith.tcginventory.scans.ScanRowItem;
import com.jordansimsmith.tcginventory.settings.SettingsItem;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class DynamoDbItemSchemasTest {
  private static final Instant CREATED_AT = Instant.ofEpochSecond(1_700_000_000);
  private static final Instant UPDATED_AT = Instant.ofEpochSecond(1_700_000_100);

  @Test
  void shouldRoundTripSkuItemWithGsiKeysAndTimestamp() {
    var item =
        SkuItem.create(
            "jordan",
            "scryfall-1#normal#NM",
            "scryfall-1",
            "normal",
            "NM",
            "Elvish Aberration",
            "a25",
            "Masters 25",
            "167",
            "fetchtcg-card-1",
            "20.00");
    item.setFetchtcgSetId(78);
    item.setVersion(7);

    var roundTripped = roundTrip(SkuItem.class, item);

    assertThat(roundTripped.getPk()).isEqualTo("USER#jordan#SKU#scryfall-1#normal#NM");
    assertThat(roundTripped.getSk()).isEqualTo("SKU");
    assertThat(roundTripped.getGsi1pk()).isEqualTo("USER#jordan#DIRTY");
    assertThat(roundTripped.getGsi1sk()).isEqualTo("SKU#scryfall-1#normal#NM");
    assertThat(roundTripped.getGsi2pk()).isEqualTo("USER#jordan#SKUS");
    assertThat(roundTripped.getGsi2sk()).startsWith("NAME#elvish aberration#");
    assertThat(roundTripped.getLastPublishedAt()).isNull();
  }

  @Test
  void shouldRoundTripUnitItemWithPhotosAndGsiKey() {
    var item =
        UnitItem.create("jordan", "scryfall-1#normal#NM", 4242, "in_stock", "import-1", CREATED_AT);
    item.setUpdatedAt(UPDATED_AT);
    item.setPhotos(
        List.of(
            UnitItem.Photo.create("photo-1", null),
            UnitItem.Photo.create("photo-2", "https://listing.example/photo-2.jpg")));

    var roundTripped = roundTrip(UnitItem.class, item);

    assertThat(roundTripped.getPk()).isEqualTo("USER#jordan#SKU#scryfall-1#normal#NM");
    assertThat(roundTripped.getSk()).isEqualTo("UNIT#0000004242");
    assertThat(roundTripped.getGsi3pk()).isEqualTo("USER#jordan#UNITS");
    assertThat(roundTripped.getSequenceNumber()).isEqualTo(4242);
    assertThat(roundTripped.getCreatedAt()).isEqualTo(CREATED_AT);
    assertThat(roundTripped.getUpdatedAt()).isEqualTo(UPDATED_AT);
    assertThat(roundTripped.getPhotos()).containsExactlyElementsOf(item.getPhotos());
  }

  @Test
  void shouldRoundTripImportItemWithNullableAttributes() {
    var item = ImportItem.create("jordan", "import-1", "cards.csv", 3, null, CREATED_AT);
    item.setUpdatedAt(UPDATED_AT);

    var roundTripped = roundTrip(ImportItem.class, item);

    assertThat(roundTripped.getPk()).isEqualTo("USER#jordan");
    assertThat(roundTripped.getSk()).isEqualTo("IMPORT#import-1");
    assertThat(roundTripped.getJobId()).isNull();
    assertThat(roundTripped.getError()).isNull();
    assertThat(roundTripped.getCreatedAt()).isEqualTo(CREATED_AT);
    assertThat(roundTripped.getUpdatedAt()).isEqualTo(UPDATED_AT);
    assertThat(roundTrippedMap(ImportItem.class, item))
        .doesNotContainKeys("gsi1pk", "gsi1sk", "gsi2pk", "gsi2sk", "gsi3pk");
  }

  @Test
  void shouldRoundTripImportRowItemWithPhotos() {
    var item =
        ImportRowItem.create(
            "jordan",
            "import-1",
            1,
            "Llanowar Elves",
            "dom",
            "Dominaria",
            "168",
            "normal",
            "NM",
            "scryfall-1",
            "en");
    item.setDecision("keep");
    item.setSuggestedPrice("20.00");
    item.setSequenceNumber(12);
    item.setPhotos(List.of(ImportRowItem.Photo.create("photo-1", null)));

    var roundTripped = roundTrip(ImportRowItem.class, item);

    assertThat(roundTripped.getPk()).isEqualTo("USER#jordan#IMPORT#import-1");
    assertThat(roundTripped.getSk()).isEqualTo("ROW#0000000001");
    assertThat(roundTripped.getDecision()).isEqualTo("keep");
    assertThat(roundTripped.getSequenceNumber()).isEqualTo(12);
    assertThat(roundTripped.getPhotos()).containsExactlyElementsOf(item.getPhotos());
  }

  @Test
  void shouldRoundTripScanItemWithTimestamps() {
    var item = ScanItem.create("jordan", "scan-1", "LP", "foil", 2, CREATED_AT);
    item.setCatalogVersion(36);
    item.setUpdatedAt(UPDATED_AT);

    var roundTripped = roundTrip(ScanItem.class, item);

    assertThat(roundTripped.getPk()).isEqualTo("USER#jordan");
    assertThat(roundTripped.getSk()).isEqualTo("SCAN#scan-1");
    assertThat(roundTripped.getStatus()).isEqualTo("uploading");
    assertThat(roundTripped.getCatalogVersion()).isEqualTo(36);
    assertThat(roundTripped.getCreatedAt()).isEqualTo(CREATED_AT);
    assertThat(roundTripped.getUpdatedAt()).isEqualTo(UPDATED_AT);
  }

  @Test
  void shouldRoundTripScanRowItemWithSuggestions() {
    var item = ScanRowItem.create("jordan", "scan-1", 1, "001.jpg", 483200L, "scans/scan-1/1.jpg");
    item.setNeedsReview(true);
    item.setSuggestions(
        List.of(
            ScanRowItem.ScanSuggestion.create("scryfall-1", "Ragavan, Nimble Pilferer", 0.83),
            ScanRowItem.ScanSuggestion.create("scryfall-2", "Dragon's Rage Channeler", 0.71)));
    item.setError("ambiguous match");

    var roundTripped = roundTrip(ScanRowItem.class, item);

    assertThat(roundTripped.getPk()).isEqualTo("USER#jordan#SCAN#scan-1");
    assertThat(roundTripped.getSk()).isEqualTo("ROW#000001");
    assertThat(roundTripped.getNeedsReview()).isTrue();
    assertThat(roundTripped.getSuggestions()).containsExactlyElementsOf(item.getSuggestions());
    assertThat(roundTripped.getError()).isEqualTo("ambiguous match");
  }

  @Test
  void shouldRoundTripOrderItemWithBuyerAddress() {
    var item =
        OrderItem.create(
            "jordan",
            "100000",
            "awaiting_payment",
            "ACCEPTED",
            "AWAITING_PAYMENT",
            "delivery",
            "Buyer",
            OrderItem.BuyerAddress.create("1 Main St", null, "Suburb", "City", "1234", "NZ"),
            "Economy Tracked",
            "20.00",
            "[]",
            CREATED_AT);
    item.setUpdatedAt(UPDATED_AT);

    var roundTripped = roundTrip(OrderItem.class, item);

    assertThat(roundTripped.getPk()).isEqualTo("USER#jordan");
    assertThat(roundTripped.getSk()).isEqualTo("ORDER#00000000000000100000");
    assertThat(roundTripped.getBuyerAddress()).isEqualTo(item.getBuyerAddress());
    assertThat(roundTripped.getCreatedAt()).isEqualTo(CREATED_AT);
    assertThat(roundTripped.getUpdatedAt()).isEqualTo(UPDATED_AT);
  }

  @Test
  void shouldRejectNonNumericOrderIds() {
    assertThatThrownBy(() -> OrderItem.formatSk("abc"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldRoundTripJobItemWithNullableContinuation() {
    var item = JobItem.create("jordan", "job-1", "appraise", "import-1", CREATED_AT);
    item.setUpdatedAt(UPDATED_AT);
    item.setContinuation(null);
    item.setProcessedCount(2);

    var roundTripped = roundTrip(JobItem.class, item);

    assertThat(roundTripped.getPk()).isEqualTo("USER#jordan");
    assertThat(roundTripped.getSk()).isEqualTo("JOB#job-1");
    assertThat(roundTripped.getContinuation()).isNull();
    assertThat(roundTripped.getProcessedCount()).isEqualTo(2);
    assertThat(roundTripped.getCreatedAt()).isEqualTo(CREATED_AT);
    assertThat(roundTripped.getUpdatedAt()).isEqualTo(UPDATED_AT);
  }

  @Test
  void shouldRoundTripSettingsItem() {
    var item = SettingsItem.create("jordan", UPDATED_AT);
    item.setTrackOrdersAfter(CREATED_AT);

    var roundTripped = roundTrip(SettingsItem.class, item);

    assertThat(roundTripped.getPk()).isEqualTo("USER#jordan");
    assertThat(roundTripped.getSk()).isEqualTo("SETTINGS");
    assertThat(roundTripped.getTrackOrdersAfter()).isEqualTo(CREATED_AT);
    assertThat(roundTripped.getUpdatedAt()).isEqualTo(UPDATED_AT);
  }

  @Test
  void shouldRoundTripReportItem() {
    var item = ReportItem.create("jordan", "{\"total\":1}", "audit-1", UPDATED_AT);

    var roundTripped = roundTrip(ReportItem.class, item);

    assertThat(roundTripped.getPk()).isEqualTo("USER#jordan");
    assertThat(roundTripped.getSk()).isEqualTo("REPORT");
    assertThat(roundTripped.getReport()).isEqualTo("{\"total\":1}");
    assertThat(roundTripped.getAsOfAuditUlid()).isEqualTo("audit-1");
    assertThat(roundTripped.getUpdatedAt()).isEqualTo(UPDATED_AT);
  }

  @Test
  void shouldRoundTripAuditItem() {
    var item = new AuditItem();
    item.setPk(AuditItem.formatPk("jordan"));
    item.setSk("audit-1");
    item.setEventType("unit_created");
    item.setImportId("import-1");
    item.setSkuId("scryfall-1#normal#NM");
    item.setSequenceNumber(42);
    item.setOrderId("100000");
    item.setDecisionReason("keep");
    item.setCreatedAt(CREATED_AT);

    var roundTripped = roundTrip(AuditItem.class, item);

    assertThat(roundTripped.getPk()).isEqualTo("USER#jordan#AUDIT");
    assertThat(roundTripped.getSk()).isEqualTo("audit-1");
    assertThat(roundTripped.getSequenceNumber()).isEqualTo(42);
    assertThat(roundTripped.getCreatedAt()).isEqualTo(CREATED_AT);
  }

  @Test
  void shouldRoundTripSequenceCounterItem() {
    var item = new SequenceCounterItem();
    item.setPk(SequenceCounterItem.formatPk("jordan"));
    item.setSk(SequenceCounterItem.formatSk());
    item.setNextSequenceNumber(4243);

    var roundTripped = roundTrip(SequenceCounterItem.class, item);

    assertThat(roundTripped.getPk()).isEqualTo("USER#jordan");
    assertThat(roundTripped.getSk()).isEqualTo("COUNTER#SEQUENCE");
    assertThat(roundTripped.getNextSequenceNumber()).isEqualTo(4243);
  }

  private static <T> T roundTrip(Class<T> itemClass, T item) {
    var schema = TableSchema.fromBean(itemClass);
    return schema.mapToItem(schema.itemToMap(item, true));
  }

  private static <T> Map<String, AttributeValue> roundTrippedMap(Class<T> itemClass, T item) {
    return TableSchema.fromBean(itemClass).itemToMap(item, true);
  }
}
