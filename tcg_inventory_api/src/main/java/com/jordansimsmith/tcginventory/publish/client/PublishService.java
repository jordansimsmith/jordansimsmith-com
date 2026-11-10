package com.jordansimsmith.tcginventory.publish.client;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;

public interface PublishService {
  CreatePublishResponse createPublish(RequestContext ctx, CreatePublishRequest request)
      throws ServiceException;

  GetPublishResponse getPublish(RequestContext ctx, GetPublishRequest request)
      throws ServiceException;
}
