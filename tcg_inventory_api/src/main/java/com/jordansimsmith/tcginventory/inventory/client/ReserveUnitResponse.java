package com.jordansimsmith.tcginventory.inventory.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ReserveUnitResponse(@JsonProperty("sequence_number") int sequenceNumber) {}
