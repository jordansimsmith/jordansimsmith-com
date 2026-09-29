package com.jordansimsmith.tcginventory.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import javax.annotation.Nullable;

public record CatalogCard(
    @JsonProperty("game") String game,
    @JsonProperty("external_source") String externalSource,
    @JsonProperty("external_id") String externalId,
    @JsonProperty("name") String name,
    @JsonProperty("set_code") String setCode,
    @JsonProperty("set_name") String setName,
    @JsonProperty("collector_number") String collectorNumber,
    @JsonProperty("image_urls") ImageUrls imageUrls,
    @JsonProperty("available_finishes") List<String> availableFinishes) {
  public record ImageUrls(
      @JsonProperty("small") @Nullable String small,
      @JsonProperty("normal") @Nullable String normal) {}
}
