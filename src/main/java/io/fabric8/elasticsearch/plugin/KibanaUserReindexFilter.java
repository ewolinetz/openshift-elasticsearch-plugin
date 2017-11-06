/**
 * Copyright (C) 2015 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fabric8.elasticsearch.plugin;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang.ObjectUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.get.MultiGetRequest;
import org.elasticsearch.action.get.MultiGetRequest.Item;
import org.elasticsearch.action.support.ActionFilter;
import org.elasticsearch.action.support.ActionFilterChain;
import org.elasticsearch.action.support.single.shard.SingleShardRequest;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;

import io.fabric8.elasticsearch.plugin.OpenshiftRequestContextFactory.OpenshiftRequestContext;

public class KibanaUserReindexFilter implements ActionFilter, ConfigurationSettings {

    private static final Logger LOG = Loggers.getLogger(KibanaUserReindexFilter.class);
    private final String defaultKibanaIndex;
    private final ThreadContext threadContext;

    public KibanaUserReindexFilter(final PluginSettings settings, final ThreadPool threadPool) {
        this.defaultKibanaIndex = settings.getDefaultKibanaIndex();
        this.threadContext = threadPool.getThreadContext();
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public void apply(Task task, String action, ActionRequest request, ActionListener listener,
            ActionFilterChain chain) {
        try {
            OpenshiftRequestContext userContext = (OpenshiftRequestContext) ObjectUtils
                    .defaultIfNull(threadContext.getTransient(OPENSHIFT_REQUEST_CONTEXT), OpenshiftRequestContext.EMPTY);
            final String user = userContext.getUser();
            final String kibanaIndex = userContext.getKibanaIndex();

            if (StringUtils.isNotEmpty(user)) {
                if (request instanceof SingleShardRequest && ((SingleShardRequest)request).index().equalsIgnoreCase(defaultKibanaIndex)) {
                    LOG.debug("Request is for a kibana index. Updating to '{}' for user '{}'", kibanaIndex, user);
                    // update the request URI here
                    ((SingleShardRequest)request).index(kibanaIndex);
                } else if (request instanceof MultiGetRequest) {
                    LOG.debug("_mget Request for a kibana index. Updating to '{}' for user '{}'", kibanaIndex, user);
                    MultiGetRequest mgetRequest = (MultiGetRequest)request;
                    for (Item item : mgetRequest) {
                        item.index(kibanaIndex);
                    }
                }
            }

        } catch (Exception e) {
            LOG.error("Error handling request in OpenShift SearchGuard filter", e);
        } finally {
            chain.proceed(task, action, request, listener);
        }
    }

    @Override
    public int order() {
        // need to run last
        return Integer.MAX_VALUE;
    }

    public static String getUsernameHash(String username) {
        return DigestUtils.sha1Hex(username);
    }
}