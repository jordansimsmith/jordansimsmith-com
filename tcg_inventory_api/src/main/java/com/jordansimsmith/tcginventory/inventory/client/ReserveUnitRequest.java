package com.jordansimsmith.tcginventory.inventory.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ReserveUnitRequest(
    @JsonProperty("operation_id") String operationId,
    @JsonProperty("reservation_id") String reservationId,
    @JsonProperty("sku_id") String skuId) {}
