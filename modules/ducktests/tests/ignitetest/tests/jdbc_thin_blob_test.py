# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""
This module contains client queries tests.
"""
from ducktape.mark import parametrize

from ignitetest.services.ignite import IgniteService
from ignitetest.services.ignite_app import IgniteApplicationService
from ignitetest.services.utils.control_utility import ControlUtility
from ignitetest.services.utils.ignite_configuration import IgniteConfiguration, IgniteThinJdbcConfiguration
from ignitetest.services.utils.ignite_configuration.cache import CacheConfiguration
from ignitetest.services.utils.ignite_configuration.data_storage import DataRegionConfiguration, \
    DataStorageConfiguration
from ignitetest.services.utils.jmx_utils import JmxClient
from ignitetest.services.utils.ssl.client_connector_configuration import ClientConnectorConfiguration
from ignitetest.utils import cluster, ignite_versions
from ignitetest.utils.ignite_test import IgniteTest
from ignitetest.utils.version import DEV_BRANCH, IgniteVersion


class JdbcThinLobTest(IgniteTest):
    """
    cluster - cluster size.
    JAVA_CLIENT_CLASS_NAME - running classname.
    to use with ssl enabled:
    export GLOBALS='{"ssl":{"enabled":true}}' .
    """
    @cluster(num_nodes=4)
    @ignite_versions(str(DEV_BRANCH))
    # @parametrize(blob_size=1*1024*1024*1024, clob_size=1*1024*1024*1024, server_heap=2, client_heap=1)
    # @parametrize(clob_size=512*1024*1024, blob_size=1, server_heap=8, client_heap=8)
    @parametrize(clob_size=0, blob_size=1*1024*1024*1024, server_heap=12, client_heap=12)
    # @parametrize(clob_size=256*1024*1024, blob_size=0, server_heap=12, client_heap=12)
    def test_jdbc_thin_lob(self, ignite_version, blob_size, clob_size, server_heap, client_heap):
        """
        Thin client IndexQuery test.
        :param ignite_version Ignite node version.
        """

        server_config = IgniteConfiguration(version=IgniteVersion(ignite_version),
                                            client_connector_configuration=ClientConnectorConfiguration(),
                                            data_storage=DataStorageConfiguration(
                                                checkpoint_frequency=10000,
                                                metrics_enabled=True,
                                                wal_segment_size=2 * 1024 * 1024 * 1024 - 1,
                                                max_wal_archive_size=20 * 1024 * 1024 * 1024,
                                                default=DataRegionConfiguration(
                                                    persistence_enabled=True,
                                                    metrics_enabled=True,
                                                    initial_size=4 * 1024 * 1024 * 1024,
                                                    max_size=4 * 1024 * 1024 * 1024
                                                )
                                            ),

                                            caches=[
                                                CacheConfiguration(name="WITH_STATISTICS_ENABLED*",
                                                                   statistics_enabled=True,
                                                                   backups=1)
                                            ])

        ignite = IgniteService(self.test_context, server_config, 2,
                               merge_with_default=False,
                               jvm_opts=["-XX:+UseG1GC", "-XX:MaxGCPauseMillis=100",
                                         f"-Xmx{server_heap}g", f"-Xms{server_heap}g",
                                         "-Xlog:safepoint*=debug:file=/mnt/service/logs/safepoint.log"
                                         ":time,uptime,level,tags",
                                         "-Xlog:gc*=debug,gc+stats*=debug,gc+ergo*=debug"
                                         ":/mnt/service/logs/gc.log:uptime,time,level,tags"])

        ignite.start()

        ControlUtility(ignite).activate()

        address = ignite.nodes[0].account.hostname + ":" + str(server_config.client_connector_configuration.port)

        cls = "org.apache.ignite.internal.ducktest.tests.jdbc.JdbcThinLobTestApplication"

        client_insert = IgniteApplicationService(
            self.test_context,
            IgniteThinJdbcConfiguration(
                version=IgniteVersion(ignite_version),
                addresses=[address]
            ),
            java_class_name=cls,
            num_nodes=1,
            merge_with_default=False,
            jvm_opts=["-XX:+UseG1GC", "-XX:MaxGCPauseMillis=100",
                      f"-Xmx{client_heap}g", f"-Xms{client_heap}g",
                      "-Xlog:safepoint*=debug:file=/mnt/service/logs/safepoint.log"
                      ":time,uptime,level,tags",
                      "-Xlog:gc*=debug,gc+stats*=debug,gc+ergo*=debug"
                      ":/mnt/service/logs/gc.log:uptime,time,level,tags",
                      "-DappId=ignite",
                      "-Dlog4j.configDebug=true",
                      "-Dlog4j.configurationFile=file:/mnt/service/config/ignite-ducktape-log4j2.xml"],
            params={
                "blob_size": blob_size,
                "clob_size": clob_size,
                "action": "insert"
            })

        client_insert.start()

        client_select = IgniteApplicationService(
            self.test_context,
            IgniteThinJdbcConfiguration(
                version=IgniteVersion(ignite_version),
                addresses=[address]
            ),
            java_class_name=cls,
            num_nodes=1,
            merge_with_default=False,
            jvm_opts=["-XX:+UseG1GC", "-XX:MaxGCPauseMillis=100",
                      f"-Xmx{client_heap}g", f"-Xms{client_heap}g",
                      "-Xlog:safepoint*=debug:file=/mnt/service/logs/safepoint.log"
                      ":time,uptime,level,tags",
                      "-Xlog:gc*=debug,gc+stats*=debug,gc+ergo*=debug"
                      ":/mnt/service/logs/gc.log:uptime,time,level,tags",
                      "-DappId=ignite",
                      "-Dlog4j.configDebug=true",
                      "-Dlog4j.configurationFile=file:/mnt/service/config/ignite-ducktape-log4j2.xml"],
            params={
                "action": "select"
            })

        client_select.start()

        data = {
            # "CLOB": clients.extract_result("CLOB"),
            "clob_size_gb": float("{:.2f}".format(int(client_select.extract_result("CLOB_SIZE")) / 1024 / 1024 / 1024)),
            # "BLOB": clients.extract_result("BLOB"),
            "blob_size_gb": float("{:.2f}".format(int(client_select.extract_result("BLOB_SIZE")) / 1024 / 1024 / 1024)),
            "server_peak_heap_usage_gb": get_peak_memory_usage(ignite.nodes),
            "client_insert_peak_heap_usage_gb": get_peak_memory_usage(client_insert.nodes),
            "client_select_peak_heap_usage_gb": get_peak_memory_usage(client_select.nodes)
        }

        client_insert.stop()
        client_select.stop()

        ignite.stop()

        return data


def get_peak_memory_usage(nodes):
    def node_peak_memory_usage(node):
        client = JmxClient(node)

        eden_mbean = client.find_mbean('.*G1 Eden Space,type=MemoryPool', domain="java.lang")
        old_mbean = client.find_mbean('.*G1 Old Gen,type=MemoryPool', domain="java.lang")
        survivor_mbean = client.find_mbean('.*G1 Survivor Space,type=MemoryPool', domain="java.lang")

        return float("{:.2f}".format((int(next(client.mbean_attribute(eden_mbean.name, 'PeakUsage.used'))) +
                                      int(next(client.mbean_attribute(old_mbean.name, 'PeakUsage.used'))) +
                                      int(next(client.mbean_attribute(survivor_mbean.name, 'PeakUsage.used')))) /
                                     1024 / 1024 / 1024))

    return {node.name: node_peak_memory_usage(node) for node in nodes}
