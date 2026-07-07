package com.jordansimsmith.pricetracker;

import static org.assertj.core.api.Assertions.assertThat;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

public class PriceExtractorTest {

  @Test
  void chemistWarehouseExtractorShouldExtractPrice() {
    // arrange
    var html =
        """
        <html>
          <body>
            <div class="product_details">
              <div class="Price">
                <div class="product__price">$99.99</div>
              </div>
            </div>
          </body>
        </html>
        """;
    var document = Jsoup.parse(html);
    var extractor = new ChemistWarehousePriceExtractor();

    // act
    var price = extractor.extractPrice(document);

    // assert
    assertThat(price).isEqualTo(99.99);
  }

  @Test
  void chemistWarehouseExtractorShouldReturnNullWhenElementNotFound() {
    // arrange
    var html = "<html><body></body></html>";
    var document = Jsoup.parse(html);
    var extractor = new ChemistWarehousePriceExtractor();

    // act
    var price = extractor.extractPrice(document);

    // assert
    assertThat(price).isNull();
  }

  @Test
  void chemistWarehouseExtractorShouldReturnNullWhenPriceIsInvalid() {
    // arrange
    var html =
        """
        <html>
          <body>
            <div class="product_details">
              <div class="Price">
                <div class="product__price">Invalid Price</div>
              </div>
            </div>
          </body>
        </html>
        """;
    var document = Jsoup.parse(html);
    var extractor = new ChemistWarehousePriceExtractor();

    // act
    var price = extractor.extractPrice(document);

    // assert
    assertThat(price).isNull();
  }

  @Test
  void nzProteinExtractorShouldExtractPrice() {
    // arrange
    var html =
        """
        <html>
          <body>
            <div itemprop="price">84.95</div>
          </body>
        </html>
        """;
    var document = Jsoup.parse(html);
    var extractor = new NzProteinPriceExtractor();

    // act
    var price = extractor.extractPrice(document);

    // assert
    assertThat(price).isEqualTo(84.95);
  }

  @Test
  void nzProteinExtractorShouldReturnNullWhenElementNotFound() {
    // arrange
    var html = "<html><body></body></html>";
    var document = Jsoup.parse(html);
    var extractor = new NzProteinPriceExtractor();

    // act
    var price = extractor.extractPrice(document);

    // assert
    assertThat(price).isNull();
  }

  @Test
  void nzProteinExtractorShouldReturnNullWhenPriceIsInvalid() {
    // arrange
    var html =
        """
        <html>
          <body>
            <div itemprop="price">Invalid Price</div>
          </body>
        </html>
        """;
    var document = Jsoup.parse(html);
    var extractor = new NzProteinPriceExtractor();

    // act
    var price = extractor.extractPrice(document);

    // assert
    assertThat(price).isNull();
  }

  @Test
  void sportsfuelExtractorShouldExtractPrice() {
    // arrange
    var html =
        """
        <html>
          <body>
            <div class="product-info__price">
              <div class="price price--on-sale">
                <div class="price__default">
                  <strong class="price__current price__current--sale">$61.11</strong>
                  <s class="price__was">$67.90</s>
                </div>
              </div>
            </div>
          </body>
        </html>
        """;
    var document = Jsoup.parse(html);
    var extractor = new SportsfuelPriceExtractor();

    // act
    var price = extractor.extractPrice(document);

    // assert
    assertThat(price).isEqualTo(61.11);
  }

  @Test
  void sportsfuelExtractorShouldReturnNullWhenElementNotFound() {
    // arrange
    var html = "<html><body></body></html>";
    var document = Jsoup.parse(html);
    var extractor = new SportsfuelPriceExtractor();

    // act
    var price = extractor.extractPrice(document);

    // assert
    assertThat(price).isNull();
  }

  @Test
  void sportsfuelExtractorShouldReturnNullWhenPriceIsInvalid() {
    // arrange
    var html =
        """
        <html>
          <body>
            <div class="product-info__price">
              <div class="price__default">
                <strong class="price__current">Unavailable</strong>
              </div>
            </div>
          </body>
        </html>
        """;
    var document = Jsoup.parse(html);
    var extractor = new SportsfuelPriceExtractor();

    // act
    var price = extractor.extractPrice(document);

    // assert
    assertThat(price).isNull();
  }
}
