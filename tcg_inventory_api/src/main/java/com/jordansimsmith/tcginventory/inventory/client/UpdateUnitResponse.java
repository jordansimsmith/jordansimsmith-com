package com.jordansimsmith.tcginventory.inventory.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UpdateUnitResponse(@JsonProperty("sku_id") String skuId) {}
