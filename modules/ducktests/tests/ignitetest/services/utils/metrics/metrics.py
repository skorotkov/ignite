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
# limitations under the License

from typing import NamedTuple

from ignitetest.services.utils.bean import Bean

METRICS_KEY = "metrics"

ENABLED = "enabled"

OPENCENSYS_TEMPLATE_FILE = "opencensys_metrics_beans_macro.j2"
OPENCENSYS_KEY = "opencensys"
OPENCENSYS_NAME = "OpencensysMetrics"

JMX_KEY = "jmx"


class OpencensysMetrics(NamedTuple):
    period: int
    port: int
    name: str

    @staticmethod
    def enabled(globals):
        return METRICS_KEY in globals and OPENCENSYS_KEY in globals[METRICS_KEY] and \
                globals[METRICS_KEY][OPENCENSYS_KEY][ENABLED]

    @staticmethod
    def from_globals(globals):
        if OpencensysMetrics.enabled(globals):
            return OpencensysMetrics(period=globals[METRICS_KEY][OPENCENSYS_KEY].get("period", 1000),
                                     port=globals[METRICS_KEY][OPENCENSYS_KEY].get("port", 8082),
                                     name=OPENCENSYS_NAME)
        else:
            return None

    @staticmethod
    def add_to_config(config, globals):
        if config.metrics_update_frequency is None:
            config = config._replace(metrics_update_frequency=1000)

        metrics_params = OpencensysMetrics.from_globals(globals)
        config.metric_exporters.add(Bean("org.apache.ignite.spi.metric.opencensus.OpenCensusMetricExporterSpi",
                                         period=metrics_params.period))

        if not any((bean[1].name and bean[1].name == OPENCENSYS_NAME) for bean in config.ext_beans):
            config.ext_beans.append((OPENCENSYS_TEMPLATE_FILE, metrics_params))

        return config


class JmxMetrics:

    @staticmethod
    def enabled(globals):
        return METRICS_KEY in globals and JMX_KEY in globals[METRICS_KEY] and \
               globals[METRICS_KEY][JMX_KEY][ENABLED]

    @staticmethod
    def add_to_config(config):
        if config.metrics_update_frequency is None:
            config = config._replace(metrics_update_frequency=1000)

        config.metric_exporters.add("org.apache.ignite.spi.metric.jmx.JmxMetricExporterSpi")

        return config
