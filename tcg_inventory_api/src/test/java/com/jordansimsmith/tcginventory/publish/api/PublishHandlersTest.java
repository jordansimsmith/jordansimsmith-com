package com.jordansimsmith.tcginventory.publish.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.publish.client.CreatePublishRequest;
import com.jordansimsmith.tcginventory.publish.client.GetPublishRequest;
import com.jordansimsmith.tcginventory.publish.client.GetPublishResponse;
import com.jordansimsmith.tcginventory.publish.client.PublishService;
import com.jordansimsmith.tcginventory.publish.client.PublishStatus;
import org.junit.jupiter.api.Test;

class PublishHandlersTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final RequestContext ctx = new RequestContext("alice");
  private final RequestContextFactory requestContextFactory = mock(RequestContextFactory.class);
  private final HttpResponseFactory httpResponseFactory =
      new HttpResponseFactory.Builder(objectMapper)
          .withAllowedOrigin("https://example.test")
          .build();
  private final TcgInventoryFactory factory = mock(TcgInventoryFactory.class);
  private final PublishService publishService = mock(PublishService.class);

  PublishHandlersTest() {
    when(factory.requestContextFactory()).thenReturn(requestContextFactory);
    when(factory.httpResponseFactory()).thenReturn(httpResponseFactory);
    when(factory.publishService()).thenReturn(publishService);
    when(requestContextFactory.createCtx(any())).thenReturn(ctx);
  }

  @Test
  void createPublishShouldDelegateAndReturnBodylessAcceptedResponse() throws Exception {
    var response =
        new CreatePublishHandler(factory).handleRequest(new APIGatewayV2HTTPEvent(), null);

    assertThat(response.getStatusCode()).isEqualTo(202);
    assertThat(response.getBody()).isNull();
    assertThat(response.getHeaders())
        .containsEntry("Access-Control-Allow-Origin", "https://example.test");
    verify(publishService).createPublish(ctx, new CreatePublishRequest());
  }

  @Test
  void getPublishShouldSerializeServiceResponseDirectly() throws Exception {
    var expected = new GetPublishResponse(PublishStatus.RUNNING, 17, 100L, null, 2);
    when(publishService.getPublish(ctx, new GetPublishRequest())).thenReturn(expected);

    var response = new GetPublishHandler(factory).handleRequest(new APIGatewayV2HTTPEvent(), null);

    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(response.getHeaders())
        .containsEntry("Content-Type", "application/json; charset=utf-8")
        .containsEntry("Access-Control-Allow-Origin", "https://example.test");
    assertThat(objectMapper.readTree(response.getBody()))
        .isEqualTo(objectMapper.readTree(objectMapper.writeValueAsString(expected)));
    verify(publishService).getPublish(ctx, new GetPublishRequest());
  }

  @Test
  void getPublishShouldMapCheckedServiceErrors() throws Exception {
    when(publishService.getPublish(ctx, new GetPublishRequest()))
        .thenThrow(new NotFoundException("Not Found"));

    var response = new GetPublishHandler(factory).handleRequest(new APIGatewayV2HTTPEvent(), null);

    assertThat(response.getStatusCode()).isEqualTo(404);
    assertThat(objectMapper.readTree(response.getBody()).get("message").asText())
        .isEqualTo("Not Found");
  }

  @Test
  void createPublishShouldMapUnavailableServiceErrors() throws Exception {
    when(publishService.createPublish(ctx, new CreatePublishRequest()))
        .thenThrow(new ServiceUnavailableException("unavailable"));

    var response =
        new CreatePublishHandler(factory).handleRequest(new APIGatewayV2HTTPEvent(), null);

    assertThat(response.getStatusCode()).isEqualTo(503);
  }

  @Test
  void getPublishShouldPropagateUnexpectedFailuresWithCause() throws Exception {
    var failure = new IllegalStateException("storage failed");
    when(publishService.getPublish(ctx, new GetPublishRequest())).thenThrow(failure);

    assertThatThrownBy(
            () -> new GetPublishHandler(factory).handleRequest(new APIGatewayV2HTTPEvent(), null))
        .isInstanceOf(RuntimeException.class)
        .hasCause(failure);
  }
}
