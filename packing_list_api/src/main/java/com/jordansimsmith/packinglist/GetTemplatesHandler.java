package com.jordansimsmith.packinglist;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GetTemplatesHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(GetTemplatesHandler.class);

  @VisibleForTesting
  record TemplateItemResponse(
      @JsonProperty("name") String name,
      @JsonProperty("category") String category,
      @JsonProperty("quantity") int quantity,
      @JsonProperty("tags") List<String> tags) {}

  @VisibleForTesting
  record BaseTemplateResponse(
      @JsonProperty("base_template_id") String baseTemplateId,
      @JsonProperty("name") String name,
      @JsonProperty("items") List<TemplateItemResponse> items) {}

  @VisibleForTesting
  record VariationResponse(
      @JsonProperty("variation_id") String variationId,
      @JsonProperty("name") String name,
      @JsonProperty("items") List<TemplateItemResponse> items) {}

  @VisibleForTesting
  record GetTemplatesResponse(
      @JsonProperty("base_template") BaseTemplateResponse baseTemplate,
      @JsonProperty("variations") List<VariationResponse> variations) {}

  private final HttpResponseFactory httpResponseFactory;
  private final TemplatesFactory templatesFactory;

  public GetTemplatesHandler() {
    this(PackingListFactory.create());
  }

  @VisibleForTesting
  GetTemplatesHandler(PackingListFactory factory) {
    this.httpResponseFactory = factory.httpResponseFactory();
    this.templatesFactory = factory.templatesFactory();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event, context);
    } catch (Exception e) {
      LOGGER.error("error processing get templates request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event, Context context)
      throws Exception {
    var baseTemplate = templatesFactory.getBaseTemplate();
    var variations = templatesFactory.findVariations();

    var baseTemplateResponse = toBaseTemplateResponse(baseTemplate);
    var variationsResponse = variations.stream().map(this::toVariationResponse).toList();

    var response = new GetTemplatesResponse(baseTemplateResponse, variationsResponse);

    return httpResponseFactory.ok(response);
  }

  private BaseTemplateResponse toBaseTemplateResponse(TemplatesFactory.BaseTemplate baseTemplate) {
    var items = baseTemplate.items().stream().map(this::toTemplateItemResponse).toList();
    return new BaseTemplateResponse(baseTemplate.baseTemplateId(), baseTemplate.name(), items);
  }

  private VariationResponse toVariationResponse(TemplatesFactory.Variation variation) {
    var items = variation.items().stream().map(this::toTemplateItemResponse).toList();
    return new VariationResponse(variation.variationId(), variation.name(), items);
  }

  private TemplateItemResponse toTemplateItemResponse(TemplatesFactory.TemplateItem item) {
    return new TemplateItemResponse(item.name(), item.category(), item.quantity(), item.tags());
  }
}
