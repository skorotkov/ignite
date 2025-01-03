/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal;

import org.apache.ignite.Ignite;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.junit.Test;

/** Test for Sql plan history configuration. */
public class SqlPlanHistoryConfigTest extends GridCommonAbstractTest {
    /** Sql plan history size in the XML Spring config. */
    private static final int SQL_PLAN_HISTORY_SIZE_XML_CONFIG = 10;

    /** Checks that plan history size specified in XML config is respected. */
    @Test
    public void testXmlConfigSqlPlanHistorySize() throws Exception {
        String cfgPath = "modules/spring/src/test/config/plan-history-conf.xml";

        Ignite ignite = startGridsWithSpringCtx(2, false, cfgPath);

        assertEquals(SQL_PLAN_HISTORY_SIZE_XML_CONFIG,
            ignite.configuration().getSqlConfiguration().getSqlPlanHistorySize());

        stopAllGrids();
    }
}
