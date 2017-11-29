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

package io.fabric8.elasticsearch.util;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.SpecialPermission;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.RestRequest;

import io.fabric8.elasticsearch.plugin.ConfigurationSettings;
import io.fabric8.elasticsearch.plugin.OpenshiftClientFactory;
import io.fabric8.elasticsearch.plugin.OpenshiftRequestContextFactory.OpenshiftRequestContext;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.openshift.api.model.SubjectAccessReviewResponse;
import io.fabric8.openshift.client.NamespacedOpenShiftClient;

public class RequestUtils implements ConfigurationSettings  {
    
    private static final Logger LOGGER = Loggers.getLogger(RequestUtils.class);
    public static final String AUTHORIZATION_HEADER = "Authorization";

    private final String proxyUserHeader;
    private final OpenshiftClientFactory k8ClientFactory;

    @Inject
    public RequestUtils(final Settings settings, OpenshiftClientFactory clientFactory) {
        this.proxyUserHeader = settings.get(SEARCHGUARD_AUTHENTICATION_PROXY_HEADER, DEFAULT_AUTH_PROXY_HEADER);
        this.k8ClientFactory = clientFactory;
    }
    
    public String getUser(RestRequest request) {
        return StringUtils.defaultIfEmpty(request.header(proxyUserHeader), "");
    }
    
    public String getBearerToken(RestRequest request) {
        final String[] auth = StringUtils.defaultIfEmpty(request.header(AUTHORIZATION_HEADER), "").split(" ");
        if (auth.length >= 2 && "Bearer".equals(auth[0])) {
            return auth[1];
        }
        return "";
    }
    
    public boolean isOperationsUser(RestRequest request, String user) {
        final SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(new SpecialPermission());
        }
        return AccessController.doPrivileged(new PrivilegedAction<Boolean>(){

            @Override
            public Boolean run() {
                final String token = getBearerToken(request);
                boolean allowed = false;
                Config config = new ConfigBuilder().withOauthToken(token).build();
                try (NamespacedOpenShiftClient osClient = (NamespacedOpenShiftClient) k8ClientFactory.create(config)) {
                    LOGGER.debug("Submitting a SAR to see if '{}' is able to retrieve logs across the cluster", user);
                    SubjectAccessReviewResponse response = osClient.inAnyNamespace().subjectAccessReviews().createNew()
                            .withVerb("get").withResource("pods/log").done();
                    allowed = response.getAllowed();
                } catch (Exception e) {
                    LOGGER.error("Exception determining user's '{}' role: {}", user, e);
                } finally {
                    LOGGER.debug("User '{}' isOperationsUser: {}", user, allowed);
                }
                return allowed;
            }
            
        });
    }
        

    /**
     * Modify the request of needed
     * @param request the original request
     * @param context the Openshift context 
     */
    public RestRequest modifyRequest(RestRequest request, OpenshiftRequestContext context) {
        if(!getUser(request).equals(context.getUser())) {
            LOGGER.debug("Modifying header '{}' to be '{}'", proxyUserHeader, context.getUser());
            final Map<String, List<String>> modifiedHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            modifiedHeaders.putAll(request.getHeaders());
            modifiedHeaders.put(proxyUserHeader, Arrays.asList(context.getUser()));
            return new RestRequest(request.getXContentRegistry(), request.params(), request.path(), modifiedHeaders) {

                @Override
                public Method method() {
                    return request.method();
                }

                @Override
                public String uri() {
                    return request.uri();
                }

                @Override
                public boolean hasContent() {
                    return request.hasContent();
                }

                @Override
                public BytesReference content() {
                    return request.content();
                }
                
            };
        }
        return request;
    }
}
