package com.jordansimsmith.auctiontracker;

import com.jordansimsmith.http.HttpStubContainer;

public class TradeMeWebsiteStubContainer extends HttpStubContainer<TradeMeWebsiteStubContainer> {
  public TradeMeWebsiteStubContainer() {
    super(
        "test.properties",
        "trademewebsitestub.image.name",
        "trademewebsitestub.image.loader",
        "/opt/code/trademe-website-stub/trademe-website-stub-server_deploy.jar",
        "com.jordansimsmith.auctiontracker.TradeMeWebsiteStubServer",
        "/health");
  }
}
