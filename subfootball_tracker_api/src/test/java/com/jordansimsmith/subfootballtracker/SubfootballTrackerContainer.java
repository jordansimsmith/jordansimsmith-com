package com.jordansimsmith.subfootballtracker;

import com.jordansimsmith.localstack.LocalStackContainer;

public class SubfootballTrackerContainer extends LocalStackContainer<SubfootballTrackerContainer> {
  public SubfootballTrackerContainer() {
    super("test.properties", "subfootballtracker.image.name", "subfootballtracker.image.loader");
  }
}
