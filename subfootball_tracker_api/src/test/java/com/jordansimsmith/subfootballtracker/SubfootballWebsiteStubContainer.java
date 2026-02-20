package com.jordansimsmith.subfootballtracker;

import com.jordansimsmith.http.HttpStubContainer;

public class SubfootballWebsiteStubContainer
    extends HttpStubContainer<SubfootballWebsiteStubContainer> {

  public SubfootballWebsiteStubContainer() {
    super(
        "test.properties",
        "subfootballwebsitestub.image.name",
        "subfootballwebsitestub.image.loader",
        "/opt/code/subfootball-website-stub/subfootball-website-stub-server_deploy.jar",
        "com.jordansimsmith.subfootballtracker.SubfootballWebsiteStubServer",
        "/register");
  }
}
