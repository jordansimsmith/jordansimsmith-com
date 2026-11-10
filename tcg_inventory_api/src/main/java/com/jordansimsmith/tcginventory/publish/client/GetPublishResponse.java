package com.jordansimsmith.tcginventory.publish.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;

public record GetPublishResponse(
    @JsonProperty("status") PublishStatus status,
    @JsonProperty("published_sku_count") int publishedSkuCount,
    @JsonProperty("started_at") long startedAt,
    @JsonProperty("finished_at") @Nullable Long finishedAt,
    @JsonProperty("pending_sku_count") int pendingSkuCount) {}
