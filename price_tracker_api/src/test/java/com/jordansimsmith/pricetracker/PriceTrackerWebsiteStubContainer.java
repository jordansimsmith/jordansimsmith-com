package com.jordansimsmith.pricetracker;

import com.jordansimsmith.http.HttpStubContainer;

public class PriceTrackerWebsiteStubContainer
    extends HttpStubContainer<PriceTrackerWebsiteStubContainer> {

  public PriceTrackerWebsiteStubContainer() {
    super(
        "test.properties",
        "pricetrackerwebsitestub.image.name",
        "pricetrackerwebsitestub.image.loader",
        "/opt/code/price-tracker-website-stub/price-tracker-website-stub-server_deploy.jar",
        "com.jordansimsmith.pricetracker.PriceTrackerWebsiteStubServer",
        "/health");
  }
}
