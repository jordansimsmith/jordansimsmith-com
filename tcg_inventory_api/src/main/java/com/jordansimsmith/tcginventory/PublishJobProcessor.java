package com.jordansimsmith.tcginventory;

public class PublishJobProcessor {
  private final FetchTcgTokenMinter fetchTcgTokenMinter;
  private final OrderPhaseProcessor orderPhaseProcessor;
  private final ListingPhaseProcessor listingPhaseProcessor;

  public PublishJobProcessor(
      FetchTcgTokenMinter fetchTcgTokenMinter,
      OrderPhaseProcessor orderPhaseProcessor,
      ListingPhaseProcessor listingPhaseProcessor) {
    this.fetchTcgTokenMinter = fetchTcgTokenMinter;
    this.orderPhaseProcessor = orderPhaseProcessor;
    this.listingPhaseProcessor = listingPhaseProcessor;
  }

  public BatchResult processBatch(String user, TcgInventoryItem jobItem) {
    var bearerToken = fetchTcgTokenMinter.mint(user);
    var continuation = jobItem.getContinuation() != null ? jobItem.getContinuation() : 0;

    if (continuation == 0) {
      orderPhaseProcessor.process(user, bearerToken);
    }

    return listingPhaseProcessor.process(user, bearerToken);
  }
}
