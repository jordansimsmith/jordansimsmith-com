package com.jordansimsmith.tcginventory.inventory.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GetUnitResponse(@JsonProperty("unit") Unit unit) {}
