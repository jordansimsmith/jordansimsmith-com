package com.jordansimsmith.service;

import java.util.Objects;

public abstract sealed class ServiceException extends Exception
    permits BadRequestException,
        UnauthorizedException,
        ForbiddenException,
        NotFoundException,
        ConflictException,
        ServiceUnavailableException {
  public ServiceException(String message) {
    super(Objects.requireNonNull(message));
  }

  public ServiceException(String message, Throwable cause) {
    super(Objects.requireNonNull(message), cause);
  }
}
