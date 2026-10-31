package com.jordansimsmith.tcginventory.inventory.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GetSkuResponse(@JsonProperty("sku") Sku sku) {}
