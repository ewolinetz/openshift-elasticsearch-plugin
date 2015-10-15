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

import static io.fabric8.elasticsearch.plugin.kibana.KibanaSeed.setDashboards;
import io.fabric8.elasticsearch.plugin.ConfigurationSettings;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.openshift.api.model.Project;
import io.fabric8.openshift.client.DefaultOpenshiftClient;
import io.fabric8.openshift.client.OpenShiftClient;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.lang.ObjectUtils;
import org.apache.commons.lang.StringUtils;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestFilter;
import org.elasticsearch.rest.RestFilterChain;
import org.elasticsearch.rest.RestRequest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.Request;
import com.ning.http.client.Response;

/**
 * REST filter to update the ACL when a user
 * first makes a request
 * @author jeff.cantrill
 *
 */
public class DynamicACLFilter 
	extends RestFilter 
	implements ConfigurationSettings, SearchGuardACLActionRequestListener {

	private static final String AUTHORIZATION_HEADER = "Authorization";
	private static final String SEARCHGUARD_TYPE = "ac";
	private static final String SEARCHGUARD_ID = "ac";

	private final ObjectMapper mapper = new ObjectMapper();
	private final ESLogger logger;
	private final UserProjectCache cache;
	private final String proxyUserHeader;
	private final Client esClient;
	private final String searchGuardIndex;
	private final String kibanaIndex;
	private final String kibanaVersion;
	private final int aclSyncDelay;
	private final String userProfilePrefix;
	private final ReentrantLock lock = new ReentrantLock();
	private final Condition syncing = lock.newCondition();

	@Inject
	public DynamicACLFilter(final UserProjectCache cache, final Settings settings, final Client client, final ACLNotifierService notifierService){
		this.cache = cache;
		this.logger = Loggers.getLogger(getClass(), settings);
		this.esClient = client;
		this.proxyUserHeader = settings.get(SEARCHGUARD_AUTHENTICATION_PROXY_HEADER, DEFAULT_AUTH_PROXY_HEADER);
		this.searchGuardIndex = settings.get(SEARCHGUARD_CONFIG_INDEX_NAME, DEFAULT_SECURITY_CONFIG_INDEX);
		this.aclSyncDelay = Integer.valueOf(settings.get(OPENSHIFT_ES_ACL_DELAY_IN_MILLIS, String.valueOf(DEFAULT_ES_ACL_DELAY)));
		this.userProfilePrefix = settings.get(OPENSHIFT_ES_USER_PROFILE_PREFIX, DEFAULT_USER_PROFILE_PREFIX);
		this.kibanaIndex = settings.get(KIBANA_CONFIG_INDEX_NAME, DEFAULT_USER_PROFILE_PREFIX);
		this.kibanaVersion = settings.get(KIBANA_CONFIG_VERSION, DEFAULT_KIBANA_VERSION);
		notifierService.addActionRequestListener(this);
		logger.debug("searchGuardIndex: {}", this.searchGuardIndex);
	}
	
	@Override
	public void onSearchGuardACLActionRequest(String method) {
		logger.debug("Received notification that SearchGuard ACL was loaded");
		lock.lock();
		try{
			syncing.signalAll();
		}finally{
			lock.unlock();
		}
	}

	@Override
	public void process(RestRequest request, RestChannel channel, RestFilterChain chain) throws Exception {
		try {
			final String user = getUser(request);
			final String token = getBearerToken(request);
			if(logger.isDebugEnabled()){
				logger.debug("Handling Request...");
				logger.debug("Evaluating request for user '{}' with a {} token", user,
						(StringUtils.isNotEmpty(token) ? "non-empty" : "empty"));
				logger.debug("Cache has user: {}", cache.hasUser(user));
			}
			if (StringUtils.isNotEmpty(token) && StringUtils.isNotEmpty(user) && !cache.hasUser(user)) {
				final boolean isClusterAdmin = isClusterAdmin(token);
				Set<String> roles = new HashSet<String>();
				if(isClusterAdmin){
					request.putInContext(OPENSHIFT_ROLES, "cluster-admin");
					roles.add("cluster-admin");
				}
				if(updateCache(user, token, isClusterAdmin)){
					syncAcl();
				}
				
				//setDashboards(user, listProjectsFor(token), roles, esClient, kibanaIndex, kibanaVersion);
			}

		} catch (Exception e) {
			logger.error("Error handling request in {}", e, this.getClass().getSimpleName());
		} finally {
			chain.continueProcessing(request, channel);
		}
	}

	private String getUser(RestRequest request) {
		return (String) ObjectUtils.defaultIfNull(request.header(proxyUserHeader), "");
	}

	private String getBearerToken(RestRequest request) {
		final String[] auth = ((String) ObjectUtils.defaultIfNull(request.header(AUTHORIZATION_HEADER), "")).split(" ");
		if (auth.length >= 2 && "Bearer".equals(auth[0])) {
			return auth[1];
		}
		return "";
	}
	

	private boolean updateCache(final String user, final String token, final boolean isClusterAdmin) {
		logger.debug("Updating the cache for user '{}'", user);
		try{
			Set<String> projects = listProjectsFor(token);
			cache.update(user, projects, isClusterAdmin);
		} catch (Exception e) {
			logger.error("Error retrieving project list for '{}'",e, user);
			return false;
		}
		return true;
	}
	
	private Set<String> listProjectsFor(final String token) throws Exception{
		ConfigBuilder builder = new ConfigBuilder()
				.withOauthToken(token);
		Set<String> names = new HashSet<>();
		try(OpenShiftClient client = new DefaultOpenshiftClient(builder.build())){
			List<Project> projects = client.projects().list().getItems();
			for (Project project : projects) {
				names.add(project.getMetadata().getName());
			}
		}
		return names;
	}
	
	/*
	 * TODO - replace with SAR from fabric8 client
	 */
	private boolean isClusterAdmin(final String token){
		ConfigBuilder builder = new ConfigBuilder()
				.withOauthToken(token);
		AsyncHttpClientConfig.Builder clientBuilder = new AsyncHttpClientConfig.Builder()
			.setFollowRedirect(true)
			.setAcceptAnyCertificate(true);
		try(AsyncHttpClient client = new AsyncHttpClient(clientBuilder.build())){
			ObjectMapper mapper = new ObjectMapper();
			Map<String,Object> body = new HashMap<>();
			body.put("kind", "SubjectAccessReview");
			body.put("apiVersion", "v1");
			body.put("verb", "*");
			body.put("resource","*");
			String requestBody = mapper.writeValueAsString(body);
			Request request = client.preparePost(String.format("%soapi/%s/subjectaccessreviews", builder.getMasterUrl(), builder.getApiVersion()))
				.addHeader(AUTHORIZATION_HEADER, "Bearer " + token)
				.addHeader("Content-Type","application/json")
				.setBody(requestBody)
				.setContentLength(requestBody.length())
				.build();
			Response response = client.executeRequest(request).get();
			logger.debug("isAdminResponse {}", response);
			String responseBody = response.getResponseBody();
			logger.debug("responseBody: {}", responseBody);
			Map<String,Object> result =
			        mapper.readValue(responseBody, HashMap.class);
			return result.containsKey("allowed") && Boolean.TRUE.equals(result.get("allowed"));
		}catch(Exception e){
			logger.error("Exception determining user's role.", e);
		}
		return false;
	}
	
	private synchronized void syncAcl() {
		logger.debug("Syncing the ACL to ElasticSearch");
		try {
			logger.debug("Loading SearchGuard ACL...");
			final SearchGuardACL acl = loadAcl(esClient);
			logger.debug("Syncing from cache to ACL...");
			acl.syncFrom(cache, userProfilePrefix);
			write(esClient, acl);
		} catch (Exception e) {
			logger.error("Exception while syncing ACL with cache", e);
		}
	}
	
	private SearchGuardACL loadAcl(Client esClient) throws IOException {
		GetRequest request = esClient.prepareGet(searchGuardIndex, SEARCHGUARD_TYPE, SEARCHGUARD_ID)
				.setRefresh(true).request();
		request.putInContext(OS_ES_REQ_ID, ACL_FILTER_ID);
		GetResponse response = esClient.get(request).actionGet(); // need to worry about timeout?
		return mapper.readValue(response.getSourceAsBytes(), SearchGuardACL.class);
	}
	

	private void write(Client esClient, SearchGuardACL acl) throws JsonProcessingException, InterruptedException {
		if (logger.isDebugEnabled()) {
			logger.debug("Writing ACLs '{}'", mapper.writer(new DefaultPrettyPrinter()).writeValueAsString(acl));
		}
		UpdateRequest request = esClient.prepareUpdate(searchGuardIndex, SEARCHGUARD_TYPE, SEARCHGUARD_ID)
			.setDoc(mapper.writeValueAsBytes(acl))
			.setRefresh(true).request();
		request.putInContext(OS_ES_REQ_ID, ACL_FILTER_ID);
		esClient.update(request).actionGet();
		lock.lock();
		try{
			logger.debug("Waiting up to {} ms. to be notified that SearchGuard has refreshed the ACLs", aclSyncDelay);
			syncing.await(aclSyncDelay, TimeUnit.MILLISECONDS);
		}catch(InterruptedException e){
			logger.error("Error while awaiting notification of ACL load by SearchGuard", e);
		}finally{
			lock.unlock();
		}
	}

	@Override
	public int order() {
		// need to run before search guard
		return Integer.MIN_VALUE;
	}
	
}
