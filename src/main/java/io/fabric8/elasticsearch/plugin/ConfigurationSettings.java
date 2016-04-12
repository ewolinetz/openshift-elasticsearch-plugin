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

public interface ConfigurationSettings {

	static final String ACL_FILTER_ID = "openshift.elasticsearch";

	static final String SEARCHGUARD_AUTHENTICATION_PROXY_HEADER = "searchguard.authentication.proxy.header";
	static final String SEARCHGUARD_CONFIG_INDEX_NAME = "searchguard.config_index_name";

	static final String SEARCHGUARD_TYPE = "ac";
	static final String SEARCHGUARD_ID = "ac";

	static final String KIBANA_CONFIG_INDEX_NAME = "kibana.config_index_name";
	static final String KIBANA_CONFIG_VERSION = "kibana.version";

	/**
	 * The maximum time time in milliseconds to wait for SearchGuard to sync the ACL from
	 * a write from this plugin until load by searchguard
	 */
	static final String OPENSHIFT_ES_ACL_DELAY_IN_MILLIS = "io.fabric8.elasticsearch.acl.sync_delay_millis";
	static final String OPENSHIFT_ES_USER_PROFILE_PREFIX = "io.fabric8.elasticsearch.acl.user_profile_prefix";
	static final String OPENSHIFT_WHITELISTED_USERS = "io.fabric8.elasticsearch.authentication.users";
	static final String OPENSHIFT_ROLES = "X-OpenShift-Roles";

	static final String DEFAULT_AUTH_PROXY_HEADER = "X-Proxy-Remote-User";
	static final String DEFAULT_SECURITY_CONFIG_INDEX = "searchguard";
	static final String DEFAULT_USER_PROFILE_PREFIX = ".kibana";
	static final String [] DEFAULT_WHITELISTED_USERS = new String []{"$logging.$infra.$fluentd","$logging.$infra.$kibana","$logging.$infra.$curator"};
	static final String DEFAULT_KIBANA_VERSION = "4.1.1";

	static final int DEFAULT_ES_ACL_DELAY = 2500;
	
	/**
	 * The configurations for the initial ACL as well
	 * as what the .operations index consists of
	 */

	static final String OPENSHIFT_CONFIG_ACL_BASE = "openshift.acl.users.";
	static final String OPENSHIFT_CONFIG_ACL_NAMES = OPENSHIFT_CONFIG_ACL_BASE + "names";
	
	// Below to be used at a later time?
	static final String OPENSHIFT_CONFIG_OPS_PROJECTS = "openshift.operations.project.names";
	static final String [] DEFAULT_OPENSHIFT_OPS_PROJECTS = new String []{"default", "openshift", "openshift-infra"};
	
	/**
	 * The configurations for enabling/disabling portions of this plugin
	 * defaults to 'true' => enabled.
	 * 
	 * This need came from integrating with APIMan -- we needed to
	 * seed our initial ACL but didn't need to dynamically update the ACL or
	 * rewrite our Kibana index.
	 */
	static final String OPENSHIFT_DYNAMIC_ENABLED_FLAG = "openshift.acl.dynamic.enabled";
	static final String OPENSHIFT_KIBANA_REWRITE_ENABLED_FLAG = "openshift.kibana.rewrite.enabled";
	
	static final boolean OPENSHIFT_DYNAMIC_ENABLED_DEFAULT = true;
	static final boolean OPENSHIFT_KIBANA_REWRITE_ENABLED_DEFAULT = true;
}
