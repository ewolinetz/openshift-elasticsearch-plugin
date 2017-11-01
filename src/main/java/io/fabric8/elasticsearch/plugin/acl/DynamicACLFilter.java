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

package io.fabric8.elasticsearch.plugin.acl;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;

import org.apache.commons.lang.ObjectUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.get.MultiGetItemResponse;
import org.elasticsearch.action.get.MultiGetRequest;
import org.elasticsearch.action.get.MultiGetResponse;
import org.elasticsearch.action.support.WriteRequest.RefreshPolicy;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestHandler;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.threadpool.ThreadPool;

import com.floragunn.searchguard.action.configupdate.ConfigUpdateAction;
import com.floragunn.searchguard.action.configupdate.ConfigUpdateRequest;
import com.floragunn.searchguard.action.configupdate.ConfigUpdateResponse;

import io.fabric8.elasticsearch.plugin.ConfigurationSettings;
import io.fabric8.elasticsearch.plugin.OpenshiftRequestContextFactory;
import io.fabric8.elasticsearch.plugin.OpenshiftRequestContextFactory.OpenshiftRequestContext;
import io.fabric8.elasticsearch.plugin.PluginSettings;
import io.fabric8.elasticsearch.plugin.kibana.KibanaSeed;
import io.fabric8.elasticsearch.util.RequestUtils;

/**
 * REST filter to update the ACL when a user first makes a request
 */
public class DynamicACLFilter implements ConfigurationSettings {

    private static final Logger LOGGER = Loggers.getLogger(DynamicACLFilter.class);

    private final UserProjectCache cache;
    private final String searchGuardIndex;
    private final String kibanaVersion;
    private final ReentrantLock lock = new ReentrantLock();

    private final String kbnVersionHeader;

    private Boolean enabled;

    private final String cdmProjectPrefix;

    private KibanaSeed kibanaSeed;

    private final Client client;
    private final OpenshiftRequestContextFactory contextFactory;
    private final SearchGuardSyncStrategyFactory documentFactory;
    private final RequestUtils utils;
    private final ThreadPool threadPool;

    public DynamicACLFilter(final UserProjectCache cache, final PluginSettings settings, final KibanaSeed seed,
            final Client client, final OpenshiftRequestContextFactory contextFactory,
            final SearchGuardSyncStrategyFactory documentFactory, ThreadPool threadPool,
            final RequestUtils utils) {
    	this.threadPool = threadPool;
        this.client = client;
        this.cache = cache;
        this.kibanaSeed = seed;
        this.contextFactory = contextFactory;
        this.documentFactory = documentFactory;
        this.searchGuardIndex = settings.getSearchGuardIndex();
        this.kibanaVersion = settings.getKibanaVersion();
        this.kbnVersionHeader = settings.getKbnVersionHeader();
        this.cdmProjectPrefix = settings.getCdmProjectPrefix();
        this.enabled = settings.isEnabled();
        this.utils = utils;
    }

    public RestHandler wrap(RestHandler original, UnaryOperator<RestHandler> unaryOperator) {
        return new RestHandler() {
            
            @Override
            public void handleRequest(RestRequest request, RestChannel channel, NodeClient client) throws Exception {
                if(continueProcessing(request, channel, client))
                {
                    unaryOperator.apply(original);
                }
            }
        };
    }
    
	public boolean continueProcessing(RestRequest request, RestChannel channel, NodeClient client) throws Exception {
        boolean continueProcessing = true;
        try {
            if (enabled) {
                // grab the kibana version here out of "kbn-version" if we can
                // -- otherwise use the config one
                String kbnVersion = getKibanaVersion(request);
                final OpenshiftRequestContext requestContext = contextFactory.create(request, cache);
                request = utils.modifyRequest(request, requestContext);
                threadPool.getThreadContext().putTransient(OPENSHIFT_REQUEST_CONTEXT, requestContext);
                if (requestContext.isAuthenticated() && !cache.hasUser(requestContext.getUser(), requestContext.getToken())) {
                    if (updateCache(requestContext, kbnVersion)) {
                        kibanaSeed.setDashboards(requestContext, client, kbnVersion, cdmProjectPrefix);
                        syncAcl(requestContext);
                    }

                }
            }
        } catch (ElasticsearchSecurityException ese) {
            LOGGER.info("Could not authenticate user");
            channel.sendResponse(new BytesRestResponse(RestStatus.UNAUTHORIZED, ""));
            continueProcessing = false;
        } catch (Exception e) {
            LOGGER.error("Error handling request in {}", e, this.getClass().getSimpleName());
        }
        return continueProcessing;
    }

    private String getKibanaVersion(RestRequest request) {
        String kbnVersion = (String) ObjectUtils.defaultIfNull(request.header(kbnVersionHeader), "");
        if (StringUtils.isEmpty(kbnVersion)) {
            return kibanaVersion;
        }
        return kbnVersion;
    }

    private boolean updateCache(final OpenshiftRequestContext context, final String kbnVersion) {
        LOGGER.debug("Updating the cache for user '{}'", context.getUser());
        try {
            cache.update(context.getUser(), context.getToken(), context.getProjects(), context.isOperationsUser());
        } catch (Exception e) {
            LOGGER.error("Error updating cache for user '{}'", e, context.getUser());
            return false;
        }
        return true;
    }

    private void syncAcl(OpenshiftRequestContext context) {
        LOGGER.debug("Syncing the ACL to ElasticSearch");
        try {
            lock.lock();
            LOGGER.debug("Loading SearchGuard ACL...");

            final MultiGetRequest mget = new MultiGetRequest();
//            mget.putHeader(ConfigConstants.SG_CONF_REQUEST_HEADER, "true"); //header needed here
            mget.refresh(true);
            mget.realtime(true);
            mget.add(searchGuardIndex, SEARCHGUARD_ROLE_TYPE, SEARCHGUARD_CONFIG_ID);
            mget.add(searchGuardIndex, SEARCHGUARD_MAPPING_TYPE, SEARCHGUARD_CONFIG_ID);

            SearchGuardRoles roles = null;
            SearchGuardRolesMapping rolesMapping = null;
            MultiGetResponse response = client.multiGet(mget).actionGet();
            for (MultiGetItemResponse item : response.getResponses()) {
                if(!item.isFailed()) {
                    if(LOGGER.isDebugEnabled()){
                        LOGGER.debug("Read in {}: {}", item.getType(), XContentHelper.convertToJson(item.getResponse().getSourceAsBytesRef(), true, true, XContentType.JSON));
                    }
                    switch (item.getType()) {
                    case SEARCHGUARD_ROLE_TYPE:
                        roles = new SearchGuardRoles().load(item.getResponse().getSource());
                        break;
                    case SEARCHGUARD_MAPPING_TYPE:
                        rolesMapping = new SearchGuardRolesMapping().load(item.getResponse().getSource());
                        break;
                    }
                }else {
                    LOGGER.error("There was a failure loading document type {}", item.getFailure(), item.getType());
                }
            }

            if(roles == null || rolesMapping == null) {
                return;
            }

            LOGGER.debug("Syncing from cache to ACL...");
            RolesMappingSyncStrategy rolesMappingSync = documentFactory.createRolesMappingSyncStrategy(rolesMapping);
            rolesMappingSync.syncFrom(cache);
            
            RolesSyncStrategy rolesSync = documentFactory.createRolesSyncStrategy(roles);
            rolesSync.syncFrom(cache);

            writeAcl(roles, rolesMapping);
        } catch (Exception e) {
            LOGGER.error("Exception while syncing ACL with cache", e);
        } finally {
            lock.unlock();
        }
    }

    private void writeAcl(SearchGuardACLDocument... documents) throws Exception {

        BulkRequestBuilder builder = this.client.prepareBulk().setRefreshPolicy(RefreshPolicy.IMMEDIATE);

        for (SearchGuardACLDocument doc : documents) {
            UpdateRequest update = this.client
                    .prepareUpdate(searchGuardIndex, doc.getType(), SEARCHGUARD_CONFIG_ID)
//                    .setConsistencyLevel(WriteConsistencyLevel.DEFAULT)
                    .setDoc(doc.toXContentBuilder())
                    .request();
            builder.add(update);
            if(LOGGER.isDebugEnabled()) {
                LOGGER.debug("Built {} update request: {}", doc.getType(), XContentHelper.convertToJson(doc.toXContentBuilder().bytes(),true, true, XContentType.JSON));
            }
        }
        BulkRequest request = builder.request();
//        request.putHeader(ConfigConstants.SG_CONF_REQUEST_HEADER, "true");
        BulkResponse response = this.client.bulk(request).actionGet();

        if(!response.hasFailures()) {
            ConfigUpdateRequest confRequest = new ConfigUpdateRequest(SEARCHGUARD_INITIAL_CONFIGS);
//            confRequest.putHeader(ConfigConstants.SG_CONF_REQUEST_HEADER, "true");
            ConfigUpdateResponse cur = this.client
                    .execute(ConfigUpdateAction.INSTANCE, confRequest).actionGet();
            final int size = cur.getNodes().size();
            if (size > 0) {
                LOGGER.debug("Successfully reloaded config with '{}' nodes", size);
            }else {
                LOGGER.warn("Failed to reloaded configs", size);
            }
        }else {
            LOGGER.error("Unable to write ACL {}", response.buildFailureMessage());
        }
    }
}
