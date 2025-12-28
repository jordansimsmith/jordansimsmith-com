package com.jordansimsmith.packinglist;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class GetTemplatesHandlerTest {
  private ObjectMapper objectMapper;
  private FakeTemplatesFactory fakeTemplatesFactory;
  private GetTemplatesHandler handler;

  @BeforeEach
  void setUp() {
    var factory = PackingListTestFactory.create();
    objectMapper = factory.objectMapper();
    fakeTemplatesFactory = factory.fakeTemplatesFactory();
    fakeTemplatesFactory.reset();
    handler = new GetTemplatesHandler(factory);
  }

  @Test
  void handleRequestShouldReturnTemplatesWithCorrectStructure() throws Exception {
    // arrange
    var baseTemplate =
        new TemplatesFactory.BaseTemplate(
            "test-id",
            "test-name",
            List.of(new TemplatesFactory.TemplateItem("item1", "category1", 1, List.of())));
    var variation =
        new TemplatesFactory.Variation(
            "var-id",
            "var-name",
            List.of(new TemplatesFactory.TemplateItem("item2", "category2", 2, List.of("tag1"))));
    fakeTemplatesFactory.setBaseTemplate(baseTemplate);
    fakeTemplatesFactory.addVariation(variation);

    var event = APIGatewayV2HTTPEvent.builder().build();

    // act
    var response = handler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(response.getHeaders())
        .containsEntry("Content-Type", "application/json; charset=utf-8");

    var body =
        objectMapper.readValue(response.getBody(), GetTemplatesHandler.GetTemplatesResponse.class);
    assertThat(body.baseTemplate()).isNotNull();
    assertThat(body.variations()).isNotNull();
  }

  @Test
  void handleRequestShouldReturnBaseTemplateFromFactory() throws Exception {
    // arrange
    var baseTemplate =
        new TemplatesFactory.BaseTemplate(
            "generic",
            "generic",
            List.of(
                new TemplatesFactory.TemplateItem("passport", "travel", 1, List.of("hand luggage")),
                new TemplatesFactory.TemplateItem("toothbrush", "toiletries", 1, List.of())));
    fakeTemplatesFactory.setBaseTemplate(baseTemplate);

    var event = APIGatewayV2HTTPEvent.builder().build();

    // act
    var response = handler.handleRequest(event, null);

    // assert
    var body =
        objectMapper.readValue(response.getBody(), GetTemplatesHandler.GetTemplatesResponse.class);
    assertThat(body.baseTemplate().baseTemplateId()).isEqualTo("generic");
    assertThat(body.baseTemplate().name()).isEqualTo("generic");
    assertThat(body.baseTemplate().items()).hasSize(2);
  }

  @Test
  void handleRequestShouldReturnBaseTemplateWithExpectedCategories() throws Exception {
    // arrange
    var baseTemplate =
        new TemplatesFactory.BaseTemplate(
            "generic",
            "generic",
            List.of(
                new TemplatesFactory.TemplateItem("passport", "travel", 1, List.of()),
                new TemplatesFactory.TemplateItem("phone", "electronics", 1, List.of()),
                new TemplatesFactory.TemplateItem("toothbrush", "toiletries", 1, List.of()),
                new TemplatesFactory.TemplateItem("shirt", "clothes", 1, List.of()),
                new TemplatesFactory.TemplateItem("snacks", "misc/uncategorised", 1, List.of())));
    fakeTemplatesFactory.setBaseTemplate(baseTemplate);

    var event = APIGatewayV2HTTPEvent.builder().build();

    // act
    var response = handler.handleRequest(event, null);

    // assert
    var body =
        objectMapper.readValue(response.getBody(), GetTemplatesHandler.GetTemplatesResponse.class);
    var categories =
        body.baseTemplate().items().stream()
            .map(GetTemplatesHandler.TemplateItemResponse::category)
            .distinct()
            .toList();
    assertThat(categories)
        .containsExactlyInAnyOrder(
            "travel", "electronics", "toiletries", "clothes", "misc/uncategorised");
  }

  @Test
  void handleRequestShouldReturnVariationsFromFactory() throws Exception {
    // arrange
    var baseTemplate = new TemplatesFactory.BaseTemplate("generic", "generic", List.of());
    var tramping =
        new TemplatesFactory.Variation(
            "tramping",
            "tramping",
            List.of(new TemplatesFactory.TemplateItem("boots", "gear", 1, List.of())));
    var skiing =
        new TemplatesFactory.Variation(
            "skiing",
            "skiing",
            List.of(new TemplatesFactory.TemplateItem("ski jacket", "clothes", 1, List.of())));
    fakeTemplatesFactory.setBaseTemplate(baseTemplate);
    fakeTemplatesFactory.addVariation(tramping);
    fakeTemplatesFactory.addVariation(skiing);

    var event = APIGatewayV2HTTPEvent.builder().build();

    // act
    var response = handler.handleRequest(event, null);

    // assert
    var body =
        objectMapper.readValue(response.getBody(), GetTemplatesHandler.GetTemplatesResponse.class);
    assertThat(body.variations()).hasSize(2);

    var variationIds =
        body.variations().stream().map(GetTemplatesHandler.VariationResponse::variationId).toList();
    assertThat(variationIds).containsExactlyInAnyOrder("tramping", "skiing");
  }

  @Test
  void handleRequestShouldReturnItemsWithCorrectJsonPropertyNames() throws Exception {
    // arrange
    var baseTemplate =
        new TemplatesFactory.BaseTemplate(
            "test-id",
            "test-name",
            List.of(new TemplatesFactory.TemplateItem("item", "cat", 1, List.of())));
    var variation =
        new TemplatesFactory.Variation(
            "var-id",
            "var-name",
            List.of(new TemplatesFactory.TemplateItem("item2", "cat2", 1, List.of())));
    fakeTemplatesFactory.setBaseTemplate(baseTemplate);
    fakeTemplatesFactory.addVariation(variation);

    var event = APIGatewayV2HTTPEvent.builder().build();

    // act
    var response = handler.handleRequest(event, null);

    // assert
    var body = response.getBody();
    assertThat(body).contains("\"base_template\"");
    assertThat(body).contains("\"base_template_id\"");
    assertThat(body).contains("\"variation_id\"");
  }

  @Test
  void handleRequestShouldReturnItemWithTags() throws Exception {
    // arrange
    var baseTemplate =
        new TemplatesFactory.BaseTemplate(
            "generic",
            "generic",
            List.of(
                new TemplatesFactory.TemplateItem(
                    "passport", "travel", 1, List.of("hand luggage"))));
    fakeTemplatesFactory.setBaseTemplate(baseTemplate);

    var event = APIGatewayV2HTTPEvent.builder().build();

    // act
    var response = handler.handleRequest(event, null);

    // assert
    var body =
        objectMapper.readValue(response.getBody(), GetTemplatesHandler.GetTemplatesResponse.class);
    var passport =
        body.baseTemplate().items().stream()
            .filter(item -> item.name().equals("passport"))
            .findFirst()
            .orElseThrow();
    assertThat(passport.category()).isEqualTo("travel");
    assertThat(passport.quantity()).isEqualTo(1);
    assertThat(passport.tags()).containsExactly("hand luggage");
  }

  @Test
  void handleRequestShouldReturnItemWithQuantity() throws Exception {
    // arrange
    var baseTemplate =
        new TemplatesFactory.BaseTemplate(
            "generic",
            "generic",
            List.of(new TemplatesFactory.TemplateItem("underwear", "clothes", 7, List.of())));
    fakeTemplatesFactory.setBaseTemplate(baseTemplate);

    var event = APIGatewayV2HTTPEvent.builder().build();

    // act
    var response = handler.handleRequest(event, null);

    // assert
    var body =
        objectMapper.readValue(response.getBody(), GetTemplatesHandler.GetTemplatesResponse.class);
    var underwear =
        body.baseTemplate().items().stream()
            .filter(item -> item.name().equals("underwear"))
            .findFirst()
            .orElseThrow();
    assertThat(underwear.quantity()).isEqualTo(7);
  }

  @Test
  void handleRequestShouldReturnVariationWithOptionalTag() throws Exception {
    // arrange
    var baseTemplate = new TemplatesFactory.BaseTemplate("generic", "generic", List.of());
    var tramping =
        new TemplatesFactory.Variation(
            "tramping",
            "tramping",
            List.of(
                new TemplatesFactory.TemplateItem(
                    "sandfly tights", "clothes", 1, List.of("optional"))));
    fakeTemplatesFactory.setBaseTemplate(baseTemplate);
    fakeTemplatesFactory.addVariation(tramping);

    var event = APIGatewayV2HTTPEvent.builder().build();

    // act
    var response = handler.handleRequest(event, null);

    // assert
    var body =
        objectMapper.readValue(response.getBody(), GetTemplatesHandler.GetTemplatesResponse.class);
    var trampingVariation =
        body.variations().stream()
            .filter(v -> v.variationId().equals("tramping"))
            .findFirst()
            .orElseThrow();

    var sandflyTights =
        trampingVariation.items().stream()
            .filter(item -> item.name().equals("sandfly tights"))
            .findFirst()
            .orElseThrow();
    assertThat(sandflyTights.tags()).contains("optional");
  }
}
