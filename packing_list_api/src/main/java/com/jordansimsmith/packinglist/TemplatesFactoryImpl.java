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
            new TemplateItem("esim", "travel", 1, List.of()),
            new TemplateItem("wise card", "travel", 1, List.of()),
            new TemplateItem("bag lock", "travel", 1, List.of("hand luggage")),
            new TemplateItem("air tickets", "travel", 1, List.of("hand luggage")),
            new TemplateItem("accommodation reference", "travel", 1, List.of("hand luggage")),
            new TemplateItem("foreign cash", "travel", 1, List.of("optional", "hand luggage")),
            new TemplateItem("passport", "travel", 1, List.of("hand luggage")),
            new TemplateItem("drivers license", "travel", 1, List.of("hand luggage")),
            new TemplateItem("visa documents", "travel", 1, List.of()),
            new TemplateItem("travel insurance details", "travel", 1, List.of()),
            new TemplateItem("wallet", "travel", 1, List.of("hand luggage")),
            new TemplateItem("house/car keys", "travel", 1, List.of()),
            // electronics
            new TemplateItem("foreign plugs", "electronics", 1, List.of()),
            new TemplateItem("power strip", "electronics", 1, List.of()),
            new TemplateItem("e-reader", "electronics", 1, List.of("hand luggage")),
            new TemplateItem("e-reader charger", "electronics", 1, List.of("hand luggage")),
            new TemplateItem("e-reader books", "electronics", 1, List.of()),
            new TemplateItem("iphone", "electronics", 1, List.of("hand luggage")),
            new TemplateItem("iphone podcasts", "electronics", 1, List.of()),
            new TemplateItem("iphone tv shows", "electronics", 1, List.of()),
            new TemplateItem("airpods", "electronics", 1, List.of("hand luggage")),
            new TemplateItem("laptop", "electronics", 1, List.of("optional", "hand luggage")),
            new TemplateItem("laptop charger", "electronics", 1, List.of("optional")),
            new TemplateItem("apple watch", "electronics", 1, List.of("hand luggage")),
            new TemplateItem("apple watch charger", "electronics", 1, List.of("hand luggage")),
            new TemplateItem("iphone charger", "electronics", 1, List.of("hand luggage")),
            new TemplateItem("charging brick", "electronics", 1, List.of("hand luggage")),
            new TemplateItem("airplane bluetooth adapter", "electronics", 1, List.of("optional")),
            new TemplateItem("power bank", "electronics", 1, List.of("hand luggage")),
            new TemplateItem("hdmi cable", "electronics", 1, List.of("optional")),
            new TemplateItem("wired headphones", "electronics", 1, List.of("optional")),
            // toiletries
            new TemplateItem("toothbrush", "toiletries", 1, List.of()),
            new TemplateItem("toothpaste", "toiletries", 1, List.of()),
            new TemplateItem("hydrocortisone", "toiletries", 1, List.of()),
            new TemplateItem("locoid", "toiletries", 1, List.of()),
            new TemplateItem("vaseline", "toiletries", 1, List.of()),
            new TemplateItem("fexofast", "toiletries", 1, List.of()),
            new TemplateItem("panadol", "toiletries", 1, List.of()),
            new TemplateItem("phenergan", "toiletries", 1, List.of()),
            new TemplateItem("qv flare up cream", "toiletries", 3, List.of()),
            new TemplateItem("shampoo", "toiletries", 1, List.of("optional")),
            new TemplateItem("dental floss", "toiletries", 1, List.of()),
            new TemplateItem("dental picks", "toiletries", 1, List.of()),
            new TemplateItem("sunscreen", "toiletries", 1, List.of()),
            new TemplateItem("body wash", "toiletries", 1, List.of()),
            new TemplateItem("hairbrush", "toiletries", 1, List.of()),
            new TemplateItem("towel", "toiletries", 1, List.of()),
            new TemplateItem("face wash", "toiletries", 1, List.of("optional")),
            new TemplateItem("deoderant", "toiletries", 1, List.of()),
            new TemplateItem("packet tissues", "toiletries", 3, List.of()),
            new TemplateItem("n95 mask", "toiletries", 2, List.of()),
            new TemplateItem("laundry detergent", "toiletries", 1, List.of("optional")),
            // food
            new TemplateItem("creatine", "food", 1, List.of()),
            new TemplateItem("whey", "food", 1, List.of()),
            new TemplateItem("shaker", "food", 1, List.of()),
            new TemplateItem("protein bars", "food", 1, List.of()),
            // clothes
            new TemplateItem("spare underwear", "clothes", 1, List.of("hand luggage")),
            new TemplateItem("spare t-shirt", "clothes", 1, List.of("hand luggage")),
            new TemplateItem("spare socks", "clothes", 1, List.of("hand luggage")),
            new TemplateItem("jandals", "clothes", 1, List.of()),
            new TemplateItem("sneakers", "clothes", 1, List.of()),
            new TemplateItem("raincoat", "clothes", 1, List.of()),
            new TemplateItem("sunhat", "clothes", 1, List.of()),
            new TemplateItem("beanie", "clothes", 1, List.of("optional")),
            new TemplateItem("togs", "clothes", 1, List.of()),
            new TemplateItem("socks", "clothes", 7, List.of()),
            new TemplateItem("tshirts", "clothes", 5, List.of()),
            new TemplateItem("underwear", "clothes", 7, List.of()),
            new TemplateItem("shorts", "clothes", 3, List.of()),
            new TemplateItem("jumper", "clothes", 1, List.of()),
            new TemplateItem("pants", "clothes", 1, List.of()),
            new TemplateItem("sunglasses", "clothes", 1, List.of()),
            new TemplateItem("pajamas", "clothes", 1, List.of()),
            // gear
            new TemplateItem("bed sheets", "gear", 1, List.of("optional")),
            new TemplateItem("pillow case", "gear", 1, List.of("optional")),
            new TemplateItem("bag for dirty gear", "gear", 1, List.of()),
            new TemplateItem("airplane head restrainer", "gear", 1, List.of()),
            new TemplateItem("drink bottle", "gear", 1, List.of("hand luggage")),
            new TemplateItem("clear toiletries bag", "gear", 1, List.of("hand luggage")),
            new TemplateItem("lunch box", "gear", 1, List.of("optional")),
            new TemplateItem("sling bag", "gear", 1, List.of()),
            new TemplateItem("dry bag", "gear", 2, List.of()),
            new TemplateItem("packing cells", "gear", 1, List.of()),
            new TemplateItem("rubber gloves", "gear", 1, List.of()),
            new TemplateItem("shopping bag", "gear", 1, List.of()),
            // misc
            new TemplateItem("playing cards", "misc", 1, List.of("optional")));

    return new BaseTemplate("generic", "generic", items);
  }

  private static List<Variation> createVariations() {
    var tramping =
        new Variation(
            "tramping",
            "tramping",
            List.of(
                // toiletries
                new TemplateItem("insect repellant", "toiletries", 1, List.of()),
                new TemplateItem("first aid kit", "toiletries", 1, List.of()),
                // clothes
                new TemplateItem("thermal top", "clothes", 1, List.of()),
                new TemplateItem("thermal bottom", "clothes", 1, List.of()),
                new TemplateItem("tramping socks", "clothes", 2, List.of()),
                new TemplateItem("woollen jumper", "clothes", 1, List.of()),
                new TemplateItem("leggings", "clothes", 1, List.of()),
                new TemplateItem("tramping shirt", "clothes", 1, List.of()),
                new TemplateItem("mosquito hat", "clothes", 1, List.of("optional")),
                new TemplateItem("tramping boots", "clothes", 1, List.of()),
                new TemplateItem("overtrousers", "clothes", 1, List.of("optional")),
                new TemplateItem("foot fleece", "clothes", 1, List.of("optional")),
                // gear
                new TemplateItem("camelback bladder", "gear", 1, List.of()),
                new TemplateItem("lifestraw", "gear", 1, List.of()),
                new TemplateItem("water purifiers", "gear", 1, List.of()),
                new TemplateItem("sleeping bag", "gear", 1, List.of()),
                new TemplateItem("bedroll", "gear", 1, List.of("optional")),
                new TemplateItem("silk liner", "gear", 1, List.of()),
                new TemplateItem("tramping tent", "gear", 1, List.of("optional")),
                new TemplateItem("stove", "gear", 1, List.of("optional")),
                new TemplateItem("gas cannisters", "gear", 2, List.of("optional")),
                new TemplateItem("billy", "gear", 1, List.of()),
                new TemplateItem("bowl", "gear", 1, List.of()),
                new TemplateItem("cup", "gear", 1, List.of()),
                new TemplateItem("eating utensils", "gear", 1, List.of()),
                new TemplateItem("toilet paper", "gear", 1, List.of()),
                new TemplateItem("tea towel", "gear", 1, List.of()),
                new TemplateItem("lighter / matches", "gear", 1, List.of("optional")),
                new TemplateItem("map", "gear", 1, List.of("optional")),
                new TemplateItem("pen knife", "gear", 1, List.of("optional")),
                new TemplateItem("milk shaker", "gear", 1, List.of("optional")),
                new TemplateItem("walking poles", "gear", 1, List.of("optional")),
                new TemplateItem("torch", "gear", 1, List.of("optional")),
                new TemplateItem("headlamp", "gear", 1, List.of("optional")),
                new TemplateItem("tramping pack", "gear", 1, List.of()),
                new TemplateItem("tramping pack rain cover", "gear", 1, List.of()),
                new TemplateItem("survival blanket", "gear", 1, List.of()),
                new TemplateItem("diswashing liquid", "gear", 1, List.of("optional")),
                // food
                new TemplateItem("dehydrated meals", "food", 1, List.of("optional")),
                new TemplateItem("tramping breakfast", "food", 1, List.of()),
                new TemplateItem("tramping snacks", "food", 1, List.of()),
                new TemplateItem("emergency rations", "food", 1, List.of())));

    var camping =
        new Variation(
            "camping",
            "camping",
            List.of(
                // clothes
                new TemplateItem("swimming goggles", "clothes", 1, List.of("optional")),
                new TemplateItem("wetsuit", "clothes", 1, List.of("optional")),
                // toiletries
                new TemplateItem("insect repellant", "toiletries", 1, List.of()),
                new TemplateItem("first aid kit", "toiletries", 1, List.of()),
                // gear
                new TemplateItem("pillow", "gear", 1, List.of()),
                new TemplateItem("bedroll", "gear", 1, List.of()),
                new TemplateItem("stretcher", "gear", 1, List.of()),
                new TemplateItem("sleeping bag", "gear", 1, List.of()),
                new TemplateItem("silk liner", "gear", 1, List.of()),
                new TemplateItem("car tent", "gear", 1, List.of()),
                new TemplateItem("toilet paper", "gear", 1, List.of()),
                new TemplateItem("bowl", "gear", 1, List.of("optional")),
                new TemplateItem("cup", "gear", 1, List.of("optional")),
                new TemplateItem("eating utensils", "gear", 1, List.of("optional")),
                new TemplateItem("tea towel", "gear", 1, List.of("optional")),
                new TemplateItem("torch", "gear", 1, List.of("optional")),
                new TemplateItem("headlamp", "gear", 1, List.of("optional")),
                new TemplateItem("bucket", "gear", 1, List.of("optional")),
                new TemplateItem("picnic rug", "gear", 1, List.of("optional")),
                new TemplateItem("diswashing liquid", "gear", 1, List.of("optional")),
                new TemplateItem("snorkelling gear", "gear", 1, List.of("optional")),
                new TemplateItem("kitchen knife", "gear", 1, List.of("optional")),
                // food
                new TemplateItem("oil", "food", 1, List.of())));

    var skiing =
        new Variation(
            "skiing",
            "skiing",
            List.of(
                new TemplateItem("ski pants", "clothes", 1, List.of()),
                new TemplateItem("ski jacket", "clothes", 1, List.of()),
                new TemplateItem("ski helmet", "clothes", 1, List.of()),
                new TemplateItem("ski helmet cover", "clothes", 1, List.of()),
                new TemplateItem("ski goggles", "clothes", 1, List.of()),
                new TemplateItem("ski gloves", "clothes", 1, List.of()),
                new TemplateItem("ski glove inners", "clothes", 1, List.of()),
                new TemplateItem("ski socks", "clothes", 2, List.of()),
                new TemplateItem("balaclava", "clothes", 1, List.of("optional")),
                new TemplateItem("neck tube", "clothes", 1, List.of()),
                new TemplateItem("thermal top", "clothes", 1, List.of()),
                new TemplateItem("thermal bottom", "clothes", 1, List.of()),
                new TemplateItem("tramping boots", "clothes", 1, List.of())));

    var cycling =
        new Variation(
            "cycling",
            "cycling",
            List.of(
                // clothes
                new TemplateItem("gloves", "clothes", 1, List.of("optional")),
                new TemplateItem("bike pants", "clothes", 1, List.of()),
                // gear
                new TemplateItem("helmet", "gear", 1, List.of("optional")),
                new TemplateItem("gel seat", "gear", 1, List.of()),
                new TemplateItem("panniers", "gear", 1, List.of())));

    return List.of(tramping, camping, skiing, cycling);
  }
}
