package com.jordansimsmith.tcginventory.fetchtcg.server;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.time.Clock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

public class FetchTcgTokenProvider {
  private static final long EXPIRY_SAFETY_SECONDS = 60;

  private final HttpFetchTcgTokenExchange tokenExchange;
  private final FetchTcgCredentialStore credentialStore;
  private final Clock clock;
  private final Cache<CredentialKey, CachedBearer> bearerCache =
      CacheBuilder.newBuilder().maximumSize(1_000).expireAfterAccess(1, TimeUnit.HOURS).build();

  public FetchTcgTokenProvider(
      HttpFetchTcgTokenExchange tokenExchange,
      FetchTcgCredentialStore credentialStore,
      Clock clock) {
    this.tokenExchange = tokenExchange;
    this.credentialStore = credentialStore;
    this.clock = clock;
  }

  public synchronized String getBearer(String user) throws ServiceException {
    var refreshToken = credentialStore.getRefreshToken(user);
    if (refreshToken == null) {
      throw new ConflictException(
          "Save or re-save your FetchTCG refresh token in settings to verify your seller account.");
    }

    var key = new CredentialKey(user, fingerprint(refreshToken));
    var cached = bearerCache.getIfPresent(key);
    var now = clock.now();
    if (cached != null && now.isBefore(cached.usableUntil())) {
      return cached.bearerToken();
    }

    var exchangeStartedAt = now;
    var result = tokenExchange.exchange(refreshToken);
    if (!refreshToken.equals(result.refreshToken())) {
      credentialStore.updateRefreshToken(user, result.refreshToken());
    }
    var usableUntil =
        exchangeStartedAt.plusSeconds(result.expiresIn()).minusSeconds(EXPIRY_SAFETY_SECONDS);
    var cachedBearer = new CachedBearer(result.bearerToken(), usableUntil);
    var effectiveKey = new CredentialKey(user, fingerprint(result.refreshToken()));
    if (usableUntil.isAfter(clock.now())) {
      bearerCache.put(effectiveKey, cachedBearer);
    }
    return result.bearerToken();
  }

  private String fingerprint(String refreshToken) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(refreshToken.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable");
    }
  }

  private record CredentialKey(String user, String refreshTokenFingerprint) {
    @Override
    public String toString() {
      return "CredentialKey[user=" + user + ", refreshTokenFingerprint=<redacted>]";
    }
  }

  private record CachedBearer(String bearerToken, Instant usableUntil) {
    @Override
    public String toString() {
      return "CachedBearer[bearerToken=<redacted>, usableUntil=" + usableUntil + "]";
    }
  }
}
