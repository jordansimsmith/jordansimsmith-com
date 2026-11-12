package com.jordansimsmith.tcginventory.imports.server;

import javax.annotation.Nullable;

public record FetchTcgCardMatch(
    @Nullable String cardId, @Nullable Integer setId, @Nullable String reason) {}
