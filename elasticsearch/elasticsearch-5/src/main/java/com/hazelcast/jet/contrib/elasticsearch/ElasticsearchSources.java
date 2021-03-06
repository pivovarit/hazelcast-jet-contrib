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
import com.hazelcast.jet.pipeline.BatchSource;
import com.hazelcast.jet.pipeline.SourceBuilder;
import com.hazelcast.jet.pipeline.SourceBuilder.SourceBuffer;
import org.elasticsearch.action.search.ClearScrollRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchScrollRequest;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.search.SearchHit;

import javax.annotation.Nonnull;
import java.io.IOException;

import static com.hazelcast.jet.contrib.elasticsearch.ElasticsearchSinks.buildClient;

/**
 * Contains factory methods for Elasticsearch sources.
 */
public final class ElasticsearchSources {

    private static final String DEFAULT_SCROLL_TIMEOUT = "60s";

    private ElasticsearchSources() {
    }

    /**
     * Creates a source which queries objects using the specified Elasticsearch
     * client and the specified request supplier using scrolling method.
     *
     * @param name                  Name of the source
     * @param clientSupplier        Elasticsearch rest client supplier
     * @param searchRequestSupplier Search request supplier
     * @param scrollTimeout         scroll keep alive time
     * @param mapHitFn              maps search hits to output items
     * @param destroyFn             called upon completion to release any resource
     * @param <T>                   type of items emitted downstream
     */
    public static <T> BatchSource<T> elasticsearch(
            @Nonnull String name,
            @Nonnull SupplierEx<? extends RestClient> clientSupplier,
            @Nonnull SupplierEx<SearchRequest> searchRequestSupplier,
            @Nonnull String scrollTimeout,
            @Nonnull FunctionEx<SearchHit, T> mapHitFn,
            @Nonnull ConsumerEx<? super RestClient> destroyFn
    ) {
        return SourceBuilder
                .batch(name, ctx -> {
                    RestClient client = clientSupplier.get();
                    SearchRequest searchRequest = searchRequestSupplier.get();
                    return new SearchContext<>(client, scrollTimeout, mapHitFn, searchRequest, destroyFn);
                })
                .<T>fillBufferFn(SearchContext::fillBuffer)
                .destroyFn(SearchContext::close)
                .build();
    }

    /**
     * Convenience for {@link #elasticsearch(String, SupplierEx, SupplierEx, String, FunctionEx, ConsumerEx)}.
     * Uses {@link #DEFAULT_SCROLL_TIMEOUT} for scroll timeout, emits string
     * representation of items using {@link SearchHit#getSourceAsString()} and
     * closes the {@link RestClient} upon completion.
     */
    public static BatchSource<String> elasticsearch(
            @Nonnull String name,
            @Nonnull SupplierEx<? extends RestClient> clientSupplier,
            @Nonnull SupplierEx<SearchRequest> searchRequestSupplier
    ) {
        return elasticsearch(name, clientSupplier, searchRequestSupplier,
                DEFAULT_SCROLL_TIMEOUT, SearchHit::getSourceAsString, RestClient::close);
    }

    /**
     * Convenience for {@link #elasticsearch(String, SupplierEx, SupplierEx)}.
     * Rest client is configured with basic authentication.
     */
    public static BatchSource<String> elasticsearch(
            @Nonnull String name,
            @Nonnull String username, String password,
            @Nonnull String hostname, int port,
            @Nonnull SupplierEx<SearchRequest> searchRequestSupplier
    ) {
        return elasticsearch(name, () -> buildClient(username, password, hostname, port), searchRequestSupplier);
    }

    private static final class SearchContext<T> {

        private final RestClient client;
        private final RestHighLevelClient highLevelClient;
        private final String scrollInterval;
        private final FunctionEx<SearchHit, T> mapHitFn;
        private final ConsumerEx<? super RestClient> destroyFn;

        private SearchResponse searchResponse;

        private SearchContext(RestClient client, String scrollInterval, FunctionEx<SearchHit, T> mapHitFn,
                              SearchRequest searchRequest, ConsumerEx<? super RestClient> destroyFn) throws IOException {
            this.client = client;
            this.highLevelClient = new RestHighLevelClient(client);
            this.scrollInterval = scrollInterval;
            this.mapHitFn = mapHitFn;
            this.destroyFn = destroyFn;

            searchRequest.scroll(scrollInterval);
            searchResponse = highLevelClient.search(searchRequest);
        }

        private void fillBuffer(SourceBuffer<T> buffer) throws IOException {
            SearchHit[] hits = searchResponse.getHits().getHits();
            if (hits == null || hits.length == 0) {
                buffer.close();
                return;
            }
            for (SearchHit hit : hits) {
                T item = mapHitFn.apply(hit);
                if (item != null) {
                    buffer.add(item);
                }
            }

            SearchScrollRequest scrollRequest = new SearchScrollRequest(searchResponse.getScrollId());
            scrollRequest.scroll(scrollInterval);
            searchResponse = highLevelClient.searchScroll(scrollRequest);
        }

        private void clearScroll() throws IOException {
            ClearScrollRequest clearScrollRequest = new ClearScrollRequest();
            clearScrollRequest.addScrollId(searchResponse.getScrollId());
            highLevelClient.clearScroll(clearScrollRequest);
        }

        private void close() throws IOException {
            clearScroll();
            destroyFn.accept(client);
        }
    }
}
