package com.jordansimsmith.tcginventory.inventory.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import javax.annotation.Nullable;

public record UpdateSkuRequest(
    @JsonProperty("sku_id") String skuId, @JsonProperty("price") @Nullable BigDecimal price) {}
