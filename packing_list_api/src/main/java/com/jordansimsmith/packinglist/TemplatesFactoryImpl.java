package com.jordansimsmith.packinglist;

import java.util.List;

public class TemplatesFactoryImpl implements TemplatesFactory {
  private static final BaseTemplate BASE_TEMPLATE = createBaseTemplate();
  private static final List<Variation> VARIATIONS = createVariations();

  @Override
  public BaseTemplate getBaseTemplate() {
    return BASE_TEMPLATE;
  }

  @Override
  public List<Variation> findVariations() {
    return VARIATIONS;
  }

  private static BaseTemplate createBaseTemplate() {
    var items =
        List.of(
            // travel
            new TemplateItem("passport", "travel", 1, List.of("hand luggage")),
            new TemplateItem("driver's licence", "travel", 1, List.of("hand luggage")),
            new TemplateItem(
                "tickets / booking confirmation", "travel", 1, List.of("hand luggage")),
            new TemplateItem("wallet", "travel", 1, List.of("hand luggage")),
            new TemplateItem("house/car keys", "travel", 1, List.of()),
            // electronics
            new TemplateItem("mobile phone", "electronics", 1, List.of("hand luggage")),
            new TemplateItem("phone charger", "electronics", 1, List.of("hand luggage")),
            new TemplateItem("power bank", "electronics", 1, List.of("hand luggage")),
            new TemplateItem("earbuds", "electronics", 1, List.of("hand luggage")),
            // toiletries
            new TemplateItem("toothbrush", "toiletries", 1, List.of()),
            new TemplateItem("toothpaste", "toiletries", 1, List.of()),
            new TemplateItem("deodorant", "toiletries", 1, List.of()),
            new TemplateItem("sunscreen", "toiletries", 1, List.of()),
            new TemplateItem("moisturiser", "toiletries", 1, List.of()),
            new TemplateItem("insect repellent", "toiletries", 1, List.of()),
            new TemplateItem("personal meds / basic first aid", "toiletries", 1, List.of()),
            // clothes
            new TemplateItem("underwear", "clothes", 7, List.of()),
            new TemplateItem("socks", "clothes", 7, List.of()),
            new TemplateItem("t-shirts", "clothes", 5, List.of()),
            new TemplateItem("shorts", "clothes", 1, List.of()),
            new TemplateItem("pants", "clothes", 1, List.of()),
            new TemplateItem("jumper", "clothes", 1, List.of()),
            new TemplateItem("raincoat", "clothes", 1, List.of()),
            new TemplateItem("sleepwear", "clothes", 1, List.of()),
            // misc/uncategorised
            new TemplateItem("drink bottle", "misc/uncategorised", 1, List.of()),
            new TemplateItem("snacks", "misc/uncategorised", 1, List.of()));

    return new BaseTemplate("generic", "generic", items);
  }

  private static List<Variation> createVariations() {
    var tramping =
        new Variation(
            "tramping",
            "tramping",
            List.of(
                // gear
                new TemplateItem("sleeping bag", "gear", 1, List.of()),
                new TemplateItem("sleeping mat", "gear", 1, List.of()),
                new TemplateItem("lightweight tramping tent", "gear", 1, List.of()),
                new TemplateItem("tramping stove + fuel", "gear", 1, List.of()),
                new TemplateItem("headlamp", "gear", 1, List.of()),
                new TemplateItem("water purifier", "gear", 1, List.of()),
                new TemplateItem("dry bags", "gear", 1, List.of()),
                new TemplateItem("toilet paper", "gear", 1, List.of()),
                new TemplateItem("map", "gear", 1, List.of()),
                new TemplateItem("lighter / matches", "gear", 1, List.of("hand luggage")),
                // clothes
                new TemplateItem("tramping boots", "clothes", 1, List.of()),
                new TemplateItem("wool tramping socks", "clothes", 2, List.of()),
                new TemplateItem("sandfly tights", "clothes", 1, List.of("optional"))));

    var camping =
        new Variation(
            "camping",
            "camping",
            List.of(
                // gear
                new TemplateItem("car camping tent", "gear", 1, List.of()),
                new TemplateItem("camping stove + fuel", "gear", 1, List.of()),
                new TemplateItem("cooking utensils", "gear", 1, List.of()),
                new TemplateItem("eating utensils", "gear", 1, List.of()),
                new TemplateItem("dishwashing liquid", "gear", 1, List.of()),
                new TemplateItem("tea towel / dishcloth", "gear", 1, List.of()),
                new TemplateItem("pegs + rope", "gear", 1, List.of()),
                new TemplateItem("torch / lantern", "gear", 1, List.of()),
                new TemplateItem("solar shower", "gear", 1, List.of("optional")),
                new TemplateItem("fishing gear", "gear", 1, List.of("optional"))));

    var skiing =
        new Variation(
            "skiing",
            "skiing",
            List.of(
                // clothes
                new TemplateItem("ski pants", "clothes", 1, List.of()),
                new TemplateItem("ski jacket", "clothes", 1, List.of()),
                new TemplateItem("ski helmet", "clothes", 1, List.of()),
                new TemplateItem("ski goggles", "clothes", 1, List.of()),
                new TemplateItem("ski gloves", "clothes", 1, List.of()),
                new TemplateItem("ski glove liners", "clothes", 1, List.of("optional")),
                new TemplateItem("ski socks", "clothes", 1, List.of()),
                new TemplateItem("balaclava", "clothes", 1, List.of()),
                new TemplateItem("neck tube", "clothes", 1, List.of()),
                // gear
                new TemplateItem("knee brace", "gear", 1, List.of("optional"))));

    var cycling =
        new Variation(
            "cycling",
            "cycling",
            List.of(
                // clothes
                new TemplateItem("cycling gloves", "clothes", 1, List.of()),
                new TemplateItem("padded bike shorts", "clothes", 1, List.of()),
                // gear
                new TemplateItem("cycling helmet", "gear", 1, List.of()),
                new TemplateItem("gel seat", "gear", 1, List.of("optional")),
                new TemplateItem("panniers", "gear", 1, List.of()),
                new TemplateItem("dry bags", "gear", 1, List.of("optional"))));

    return List.of(tramping, camping, skiing, cycling);
  }
}
