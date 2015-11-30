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

import io.fabric8.elasticsearch.plugin.acl.SearchGuardACLRequestActionFilter;

import java.util.Collection;

import org.elasticsearch.action.ActionModule;
import org.elasticsearch.common.component.LifecycleComponent;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.plugins.Plugin;

import com.google.common.collect.Lists;

public class OpenShiftElasticSearchPlugin extends Plugin {

	public OpenShiftElasticSearchPlugin(){
		
	}
	@Override
	public String name() {
		return "openshift-elasticsearch-plugin";
	}

	@Override
	public String description() {
		return "OpenShift ElasticSearch Plugin";
	}

	@SuppressWarnings("rawtypes")
	@Override
	public Collection<Class<? extends LifecycleComponent>> nodeServices() {
		Collection<Class<? extends LifecycleComponent>> services = Lists.newArrayList();
		services.add(OpenShiftElasticSearchService.class);
		return services;
	}

	public void onModule(final ActionModule module) {
		module.registerFilter(ActionForbiddenActionFilter.class);
		module.registerFilter(SearchGuardACLRequestActionFilter.class);
		module.registerFilter(KibanaUserReindexAction.class);
	}

	@Override
	public Collection<Module> nodeModules() {
		Collection<Module> modules = Lists.newArrayList();
		modules.add(new OpenShiftElasticSearchModule());
		return modules;
	}
}
