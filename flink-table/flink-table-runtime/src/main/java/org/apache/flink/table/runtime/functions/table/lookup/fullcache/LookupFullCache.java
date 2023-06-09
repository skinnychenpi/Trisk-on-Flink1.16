/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.runtime.functions.table.lookup.fullcache;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.metrics.groups.CacheMetricGroup;
import org.apache.flink.table.connector.source.lookup.LookupOptions.LookupCacheType;
import org.apache.flink.table.connector.source.lookup.cache.LookupCache;
import org.apache.flink.table.connector.source.lookup.cache.trigger.CacheReloadTrigger;
import org.apache.flink.table.data.RowData;
import org.apache.flink.util.Preconditions;

import java.util.Collection;
import java.util.Collections;

/** Internal implementation of {@link LookupCache} for {@link LookupCacheType#FULL}. */
public class LookupFullCache implements LookupCache {
    private static final long serialVersionUID = 1L;

    private final CacheLoader cacheLoader;
    private final CacheReloadTrigger reloadTrigger;

    private transient volatile ReloadTriggerContext reloadTriggerContext;
    private transient volatile Throwable reloadFailCause;

    public LookupFullCache(CacheLoader cacheLoader, CacheReloadTrigger reloadTrigger) {
        this.cacheLoader = Preconditions.checkNotNull(cacheLoader);
        this.reloadTrigger = Preconditions.checkNotNull(reloadTrigger);
    }

    @Override
    public synchronized void open(CacheMetricGroup metricGroup) {
        cacheLoader.open(metricGroup);
    }

    public synchronized void open(Configuration parameters) throws Exception {
        if (reloadTriggerContext == null) {
            cacheLoader.open(parameters);
            reloadTriggerContext =
                    new ReloadTriggerContext(
                            cacheLoader,
                            th -> {
                                if (reloadFailCause == null) {
                                    reloadFailCause = th;
                                } else {
                                    reloadFailCause.addSuppressed(th);
                                }
                            });

            reloadTrigger.open(reloadTriggerContext);
            cacheLoader.awaitFirstLoad();
        }
    }

    @Override
    public Collection<RowData> getIfPresent(RowData key) {
        if (reloadFailCause != null) {
            throw new RuntimeException(reloadFailCause);
        }
        return cacheLoader.getCache().getOrDefault(key, Collections.emptyList());
    }

    @Override
    public Collection<RowData> put(RowData key, Collection<RowData> value) {
        throw new UnsupportedOperationException(
                "Lookup Full cache doesn't support public 'put' operation from the outside.");
    }

    @Override
    public void invalidate(RowData key) {
        throw new UnsupportedOperationException(
                "Lookup Full cache doesn't support public 'invalidate' operation from the outside.");
    }

    @Override
    public long size() {
        return cacheLoader.getCache().size();
    }

    @Override
    public void close() throws Exception {
        cacheLoader.close();
        reloadTrigger.close();
    }
}
