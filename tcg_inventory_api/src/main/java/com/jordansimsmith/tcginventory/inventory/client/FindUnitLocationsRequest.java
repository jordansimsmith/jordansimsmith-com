package com.jordansimsmith.tcginventory.inventory.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record FindUnitLocationsRequest(@JsonProperty("units") List<UnitReference> units) {}
