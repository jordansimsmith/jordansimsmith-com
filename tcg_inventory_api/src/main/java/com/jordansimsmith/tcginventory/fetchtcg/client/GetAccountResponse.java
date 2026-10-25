package com.jordansimsmith.tcginventory.fetchtcg.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GetAccountResponse(
    @JsonProperty("seller_username") String sellerUsername,
    @JsonProperty("credentials_updated_at") long credentialsUpdatedAt) {}
