package com.jordansimsmith.tcginventory.catalog;

import com.jordansimsmith.http.HttpStubContainer;

public class ScryfallStubContainer extends HttpStubContainer<ScryfallStubContainer> {
  public ScryfallStubContainer() {
    super(
        "test.properties",
        "tcginventoryscryfallstub.image.name",
        "tcginventoryscryfallstub.image.loader",
        "/opt/code/scryfall-stub/scryfall-stub-server_deploy.jar",
        "com.jordansimsmith.tcginventory.catalog.ScryfallStubServer",
        "/health",
        "scryfall-stub");
  }
}
