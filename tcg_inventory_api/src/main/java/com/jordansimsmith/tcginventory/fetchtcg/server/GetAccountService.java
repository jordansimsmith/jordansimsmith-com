package com.jordansimsmith.tcginventory.fetchtcg.server;

import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetAccountRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetAccountResponse;

public class GetAccountService {
  private final FetchTcgAccountRepository accountRepository;

  public GetAccountService(FetchTcgAccountRepository accountRepository) {
    this.accountRepository = accountRepository;
  }

  public GetAccountResponse getAccount(RequestContext ctx, GetAccountRequest request)
      throws ServiceException {
    var account = accountRepository.get(ctx.user());
    if (account == null) {
      throw new NotFoundException("FetchTCG account is not configured");
    }
    if (account.getSellerUsername() == null
        || account.getSellerUsername().isBlank()
        || account.getCredentialsUpdatedAt() == null) {
      throw new IllegalStateException("FetchTCG account metadata is incomplete");
    }

    return new GetAccountResponse(
        account.getSellerUsername(), account.getCredentialsUpdatedAt().getEpochSecond());
  }
}
