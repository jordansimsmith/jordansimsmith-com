package com.jordansimsmith.tcginventory.fetchtcg;

import com.jordansimsmith.http.HttpStubContainer;

public class FetchTcgStubContainer extends HttpStubContainer<FetchTcgStubContainer> {
  public FetchTcgStubContainer() {
    super(
        "test.properties",
        "tcginventoryfetchtcgstub.image.name",
        "tcginventoryfetchtcgstub.image.loader",
        "/opt/code/fetchtcg-stub/fetchtcg-stub-server_deploy.jar",
        "com.jordansimsmith.tcginventory.fetchtcg.FetchTcgStubServer",
        "/health",
        "fetchtcg-stub");
  }
}
