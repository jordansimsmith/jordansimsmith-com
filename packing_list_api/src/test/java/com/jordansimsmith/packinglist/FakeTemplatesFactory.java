package com.jordansimsmith.packinglist;

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.List;

public class FakeTemplatesFactory implements TemplatesFactory {
  private BaseTemplate baseTemplate;
  private final List<Variation> variations = new ArrayList<>();

  @Override
  public BaseTemplate getBaseTemplate() {
    Preconditions.checkNotNull(baseTemplate);
    return baseTemplate;
  }

  @Override
  public List<Variation> findVariations() {
    return variations;
  }

  public void setBaseTemplate(BaseTemplate baseTemplate) {
    this.baseTemplate = baseTemplate;
  }

  public void addVariation(Variation variation) {
    this.variations.add(variation);
  }

  public void reset() {
    this.baseTemplate = null;
    this.variations.clear();
  }
}
