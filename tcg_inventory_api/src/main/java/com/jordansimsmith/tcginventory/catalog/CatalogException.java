package com.jordansimsmith.tcginventory.catalog;

public class CatalogException extends RuntimeException {
  private CatalogException(String message) {
    super(message);
  }

  private CatalogException(String message, Throwable cause) {
    super(message, cause);
  }

  public static class BadRequest extends CatalogException {
    public BadRequest(String message) {
      super(message);
    }
  }

  public static class NotFound extends CatalogException {
    public NotFound(String message) {
      super(message);
    }
  }

  public static class Unavailable extends CatalogException {
    public Unavailable(String message) {
      super(message);
    }

    public Unavailable(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
