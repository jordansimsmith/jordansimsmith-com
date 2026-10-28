package com.jordansimsmith.tcginventory.pricing.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.fetchtcg.client.FakeFetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetCardResponse;
import com.jordansimsmith.tcginventory.pricing.client.Condition;
import com.jordansimsmith.tcginventory.pricing.client.GetPriceRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Testcontainers
class GetPriceServiceIntegrationTest {
  private static final String TABLE_NAME = "pricing_test";
  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  private static DynamoDbClient dynamoDbClient;
  private static DynamoDbTable<PricingItem> pricingTable;
  private FakeFetchTcgService fetchTcgService;

  @BeforeAll
  static void setUpBeforeClass() {
    dynamoDbClient =
        DynamoDbClient.builder()
            .endpointOverride(dynamoDbContainer.getEndpoint())
            .region(Region.AP_SOUTHEAST_2)
            .build();
    var dynamoDbEnhancedClient =
        DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
    pricingTable =
        dynamoDbEnhancedClient.table(TABLE_NAME, TableSchema.fromBean(PricingItem.class));
    DynamoDbUtils.createTable(dynamoDbClient, pricingTable);
  }

  @AfterAll
  static void tearDownAfterClass() {
    dynamoDbClient.close();
  }

  @BeforeEach
  void setUp() {
    DynamoDbUtils.reset(dynamoDbClient);
    fetchTcgService = new FakeFetchTcgService();
    fetchTcgService.seedSellerUsername("alice", "alice-seller");
    fetchTcgService.seedCard(
        "card-1", new GetCardResponse("card-1", "Card", new BigDecimal("0.10"), Map.of()));
    fetchTcgService.seedListings("card-1", List.of());
  }

  @Test
  void getPriceShouldUsePerUserFloorsAndObserveChangesBetweenCalls() throws Exception {
    // arrange
    var service = new GetPriceService(new PricingRepository(pricingTable), fetchTcgService);
    var alice = new RequestContext("alice");
    var bob = new RequestContext("bob");
    pricingTable.putItem(PricingItem.create("bob", "0.75"));

    // act
    var aliceDefault = service.getPrice(alice, new GetPriceRequest("card-1", Condition.NM));
    var bobSaved = service.getPrice(bob, new GetPriceRequest("card-1", Condition.NM));
    pricingTable.putItem(PricingItem.create("alice", "0.50"));
    var aliceUpdated = service.getPrice(alice, new GetPriceRequest("card-1", Condition.NM));
    pricingTable.putItem(PricingItem.create("bob", "0.00"));
    var bobZeroFloor = service.getPrice(bob, new GetPriceRequest("card-1", Condition.NM));

    // assert
    assertThat(aliceDefault.suggestedPrice()).isEqualByComparingTo("0.25");
    assertThat(bobSaved.suggestedPrice()).isEqualByComparingTo("0.75");
    assertThat(aliceUpdated.suggestedPrice()).isEqualByComparingTo("0.50");
    assertThat(bobZeroFloor.suggestedPrice()).isEqualByComparingTo("0.10");
  }
}
