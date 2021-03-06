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

import static io.fabric8.elasticsearch.plugin.acl.BaseRolesSyncStrategy.formatUserRoleName;
import static org.junit.Assert.assertEquals;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.Test;

public class BaseRolesSyncStrategyTest {

    @Test
    public void testFormatUserNameRoleFromEmail() {
        assertEquals("gen_user_" + DigestUtils.sha1Hex("user@email.com"), formatUserRoleName("user@email.com"));
    }

    @Test
    public void testFormatUserNameRoleThatHasSlash() {
        assertEquals("gen_user_" + DigestUtils.sha1Hex("test\\\\user"), formatUserRoleName("test\\\\user"));
    }

    @Test
    public void testFormatUserNameRoleThatHasForwardSlash() {
        assertEquals("gen_user_" + DigestUtils.sha1Hex("test/user"), formatUserRoleName("test/user"));
    }

}
