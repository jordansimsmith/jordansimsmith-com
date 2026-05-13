package com.jordansimsmith.auth;

public record AuthorizerResponse(String principalId, Object policyDocument) {}
