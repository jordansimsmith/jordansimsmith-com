package com.jordansimsmith.auth;

import java.util.Map;

public record AuthorizerEvent(
    Map<String, String> headers, Map<String, String> queryStringParameters, String methodArn) {}
