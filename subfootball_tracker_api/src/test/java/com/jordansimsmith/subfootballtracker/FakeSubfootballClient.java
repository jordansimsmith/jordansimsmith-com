package com.jordansimsmith.subfootballtracker;

public class FakeSubfootballClient implements SubfootballClient {
  private String registrationContent;

  @Override
  public String getRegistrationContent() {
    return registrationContent;
  }

  public void setRegistrationContent(String registrationContent) {
    this.registrationContent = registrationContent;
  }

  public void reset() {
    registrationContent = null;
  }
}
