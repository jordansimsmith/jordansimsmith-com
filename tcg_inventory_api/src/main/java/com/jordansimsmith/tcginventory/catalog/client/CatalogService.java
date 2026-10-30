package com.jordansimsmith.tcginventory.catalog.client;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;

public interface CatalogService {
  FindCardImagesResponse findCardImages(RequestContext ctx, FindCardImagesRequest request)
      throws ServiceException;
}
