/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.flume.sink.elasticsearch.client;

import com.google.common.annotations.VisibleForTesting;
import com.google.gson.Gson;
import org.apache.commons.lang.StringUtils;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.conf.Configurable;
import org.apache.flume.sink.elasticsearch.ElasticSearchEventSerializer;
import org.apache.flume.sink.elasticsearch.IndexNameBuilder;
import org.apache.http.client.HttpClient;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.common.bytes.BytesReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.flume.sink.elasticsearch.ElasticSearchSinkConstants.HTTP_CLIENT;
import static org.apache.flume.sink.elasticsearch.ElasticSearchSinkConstants.HTTP_CLIENT_PREFIX;
import static org.apache.flume.sink.elasticsearch.ElasticSearchSinkConstants.DEFAULT_HTTP_CLIENT_CLASS;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Rest ElasticSearch client which is responsible for sending bulks of events to
 * ElasticSearch using ElasticSearch HTTP API. This is configurable, so any
 * config params required should be taken through this.
 */
public class  ElasticSearchRestClient implements ElasticSearchClient {

  private static final String INDEX_OPERATION_NAME = "index";
  private static final String INDEX_PARAM = "_index";
  private static final String TYPE_PARAM = "_type";
  private static final String TTL_PARAM = "_ttl";
  private static final String BULK_ENDPOINT = "_bulk";

  private static final Logger logger = LoggerFactory.getLogger(ElasticSearchRestClient.class);

  private final ElasticSearchEventSerializer serializer;
  private final RoundRobinList<String> serversList;
  
  private StringBuilder bulkBuilder;

  private Context httpClientContext;
  private HttpClientBuilder httpClientBuilder;
  private HttpClient httpClient;
  
  public ElasticSearchRestClient(String[] hostNames,
      ElasticSearchEventSerializer serializer) {

    for (int i = 0; i < hostNames.length; ++i) {
      if (!hostNames[i].contains("http://") && !hostNames[i].contains("https://")) {
        hostNames[i] = "http://" + hostNames[i];
      }
    }
    this.serializer = serializer;

    serversList = new RoundRobinList<String>(Arrays.asList(hostNames));
    bulkBuilder = new StringBuilder();
  }

  @VisibleForTesting
  public ElasticSearchRestClient(String[] hostNames,
          ElasticSearchEventSerializer serializer, HttpClient client) {
    this(hostNames, serializer);
    httpClient = client;
  }

  @Override
  public void configure(Context context) {

    String clientBuilderClazz = DEFAULT_HTTP_CLIENT_CLASS;
    if (StringUtils.isNotBlank(context.getString(HTTP_CLIENT))) {
      clientBuilderClazz = context.getString(HTTP_CLIENT);
    }

    httpClientContext = new Context();
    httpClientContext.putAll(context.getSubProperties(HTTP_CLIENT_PREFIX));

    try {
      @SuppressWarnings("unchecked")
      Class<? extends Configurable> clazz = (Class<? extends Configurable>) Class
          .forName(clientBuilderClazz);
      Configurable builder = clazz.newInstance();
      if (builder instanceof HttpClientBuilder) {
        builder.configure(httpClientContext);
        httpClientBuilder = (HttpClientBuilder)builder;
      } else {
        throw new IllegalArgumentException(clientBuilderClazz
            + " is not an HttpClientBuilder");
      }
    } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
      throw new RuntimeException("Could not instantiate the HTTP client.", e);
    }

    if (httpClient == null)  {
      httpClient = httpClientBuilder.newHttpClient();
    }
  }

  @Override
  public void close() {
  }

  @Override
  public void addEvent(Event event, IndexNameBuilder indexNameBuilder, String indexType,
                       long ttlMs) throws Exception {
    BytesReference content = serializer.getContentBuilder(event).bytes();
    Map<String, Map<String, String>> parameters = new HashMap<String, Map<String, String>>();
    Map<String, String> indexParameters = new HashMap<String, String>();
    indexParameters.put(INDEX_PARAM, indexNameBuilder.getIndexName(event));
    indexParameters.put(TYPE_PARAM, indexType);
    if (ttlMs > 0) {
      indexParameters.put(TTL_PARAM, Long.toString(ttlMs));
    }
    parameters.put(INDEX_OPERATION_NAME, indexParameters);

    Gson gson = new Gson();
    synchronized (this) {
      bulkBuilder.append(gson.toJson(parameters));
      bulkBuilder.append("\n");
      bulkBuilder.append(content.toBytesArray().toUtf8());
      bulkBuilder.append("\n");
    }
  }

  @Override
  public void execute() throws Exception {
    int statusCode = 0, triesCount = 0;
    HttpResponse response = null;
    String entity;
    synchronized (this) {
      entity = bulkBuilder.toString();
      bulkBuilder = new StringBuilder();
    }

    while (statusCode != HttpStatus.SC_OK && triesCount < serversList.size()) {
      triesCount++;
      String host = serversList.get();
      String url = host + "/" + BULK_ENDPOINT;
      HttpPost httpRequest = new HttpPost(url);
      httpRequest.setEntity(new StringEntity(entity));
      response = httpClient.execute(httpRequest);
      statusCode = response.getStatusLine().getStatusCode();
      logger.info("Status code from elasticsearch: " + statusCode);
      if (response.getEntity() != null) {
        logger.debug("Status message from elasticsearch: " +
                     EntityUtils.toString(response.getEntity(), "UTF-8"));
      }
    }

    if (statusCode != HttpStatus.SC_OK) {
      if (response.getEntity() != null) {
        throw new EventDeliveryException(EntityUtils.toString(response.getEntity(), "UTF-8"));
      } else {
        throw new EventDeliveryException("Elasticsearch status code was: " + statusCode);
      }
    }
  }
}
