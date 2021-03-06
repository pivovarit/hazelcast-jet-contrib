/*
 * Copyright (c) 2008-2019, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.contrib.elasticsearch;

import com.hazelcast.function.ConsumerEx;
import com.hazelcast.function.FunctionEx;
import com.hazelcast.function.SupplierEx;
import com.hazelcast.jet.pipeline.Sink;
import com.hazelcast.jet.pipeline.SinkBuilder;
import org.apache.http.HttpHost;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;

import static org.apache.http.auth.AuthScope.ANY;

/**
 * Contains factory methods for Elasticsearch sinks.
 */
public final class ElasticsearchSinks {

    private ElasticsearchSinks() {
    }

    /**
     * Creates a sink which indexes objects using the specified Elasticsearch
     * client and the specified bulk request.
     *
     * @param name                name of the created sink
     * @param clientSupplier      Elasticsearch REST client supplier
     * @param bulkRequestSupplier bulk request supplier, will be called to obtain a
     *                            new {@link BulkRequest} instance after each call.
     * @param requestFn           creates an {@link IndexRequest}, {@link UpdateRequest}
     *                            or {@link DeleteRequest} for each object
     * @param optionsFn           obtains {@link RequestOptions} for each request
     * @param destroyFn           called upon completion to release any resource
     */
    public static <T> Sink<T> elasticsearch(
            @Nonnull String name,
            @Nonnull SupplierEx<? extends RestHighLevelClient> clientSupplier,
            @Nonnull SupplierEx<BulkRequest> bulkRequestSupplier,
            @Nonnull FunctionEx<? super T, ? extends DocWriteRequest> requestFn,
            @Nonnull FunctionEx<? super ActionRequest, RequestOptions> optionsFn,
            @Nonnull ConsumerEx<? super RestHighLevelClient> destroyFn
    ) {
        return SinkBuilder
                .sinkBuilder(name, ctx -> new BulkContext(clientSupplier.get(), bulkRequestSupplier, optionsFn, destroyFn))
                .<T>receiveFn((bulkContext, item) -> bulkContext.add(requestFn.apply(item)))
                .flushFn(BulkContext::flush)
                .destroyFn(BulkContext::close)
                .preferredLocalParallelism(2)
                .build();
    }

    /**
     * Convenience for {@link #elasticsearch(String, SupplierEx, SupplierEx,
     * FunctionEx, FunctionEx, ConsumerEx)}. Creates a new {@link BulkRequest}
     * with default options for each batch and closes the {@link
     * RestHighLevelClient} upon completion. Assumes that the {@code
     * clientSupplier} creates a new client for each call.
     */
    public static <T> Sink<T> elasticsearch(
            @Nonnull String name,
            @Nonnull SupplierEx<? extends RestHighLevelClient> clientSupplier,
            @Nonnull FunctionEx<? super T, ? extends DocWriteRequest> requestFn
    ) {
        return elasticsearch(name, clientSupplier, BulkRequest::new, requestFn,
                request -> RequestOptions.DEFAULT, RestHighLevelClient::close);
    }

    /**
     * Convenience for {@link #elasticsearch(String, SupplierEx, FunctionEx)}
     * Rest client is configured with basic authentication.
     */
    public static <T> Sink<T> elasticsearch(
            @Nonnull String name,
            @Nonnull String username,
            @Nullable String password,
            @Nonnull String hostname,
            int port,
            @Nonnull FunctionEx<? super T, ? extends DocWriteRequest> requestFn
    ) {
        return elasticsearch(name, () -> buildClient(username, password, hostname, port), requestFn);
    }

    static RestHighLevelClient buildClient(String username, String password, String hostname, int port) {
        CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(ANY, new UsernamePasswordCredentials(username, password));
        return new RestHighLevelClient(
                RestClient.builder(new HttpHost(hostname, port))
                          .setHttpClientConfigCallback(httpClientBuilder ->
                                  httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider))
        );
    }

    private static final class BulkContext {

        private final RestHighLevelClient client;
        private final SupplierEx<BulkRequest> bulkRequestSupplier;
        private final FunctionEx<? super ActionRequest, RequestOptions> optionsFn;
        private final ConsumerEx<? super RestHighLevelClient> destroyFn;

        private BulkRequest bulkRequest;

        private BulkContext(RestHighLevelClient client, SupplierEx<BulkRequest> bulkRequestSupplier,
                            FunctionEx<? super ActionRequest, RequestOptions> optionsFn,
                            ConsumerEx<? super RestHighLevelClient> destroyFn) {
            this.client = client;
            this.bulkRequestSupplier = bulkRequestSupplier;
            this.optionsFn = optionsFn;
            this.destroyFn = destroyFn;

            this.bulkRequest = bulkRequestSupplier.get();
        }

        private void add(DocWriteRequest request) {
            bulkRequest.add(request);
        }

        private void flush() throws IOException {
            BulkResponse response = client.bulk(bulkRequest, optionsFn.apply(bulkRequest));
            if (response.hasFailures()) {
                throw new ElasticsearchException(response.buildFailureMessage());
            }
            bulkRequest = bulkRequestSupplier.get();
        }

        private void close() {
            destroyFn.accept(client);
        }
    }

}
