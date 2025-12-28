package com.jordansimsmith.packinglist;

import java.util.List;

public interface TemplatesFactory {
  record TemplateItem(String name, String category, int quantity, List<String> tags) {}

  record BaseTemplate(String baseTemplateId, String name, List<TemplateItem> items) {}

  record Variation(String variationId, String name, List<TemplateItem> items) {}

  BaseTemplate getBaseTemplate();

  List<Variation> findVariations();
}
