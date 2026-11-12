package com.jordansimsmith.tcginventory.fetchtcg;

import com.jordansimsmith.http.HttpStubContainer;

public class FirebaseStubContainer extends HttpStubContainer<FirebaseStubContainer> {
  public FirebaseStubContainer() {
    super(
        "test.properties",
        "tcginventoryfirebasestub.image.name",
        "tcginventoryfirebasestub.image.loader",
        "/opt/code/firebase-stub/firebase-stub-server_deploy.jar",
        "com.jordansimsmith.tcginventory.fetchtcg.FirebaseStubServer",
        "/health",
        "firebase-stub");
  }
}
