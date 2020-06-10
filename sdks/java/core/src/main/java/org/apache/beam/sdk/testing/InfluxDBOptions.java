/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.testing;

import javax.annotation.Nullable;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;

public interface InfluxDBOptions {
  @Description("InfluxDB measurement to publish results to.")
  @Nullable
  String getInfluxMeasurement();

  void setInfluxMeasurement(@Nullable String measurement);

  @Description("InfluxDB host.")
  @Nullable
  String getInfluxHost();

  void setInfluxHost(@Nullable String host);

  @Description("InfluxDB database.")
  @Nullable
  String getInfluxDatabase();

  void setInfluxDatabase(@Nullable String database);

  @Description("Shall we export the summary to InfluxDB.")
  @Default.Boolean(false)
  boolean getExportSummaryToInfluxDB();

  void setExportSummaryToInfluxDB(boolean exportSummaryToInfluxDB);

  @Description("Base name of measurement name if using InfluxDB output.")
  @Nullable
  @Default.String("nexmark")
  String getBaseInfluxMeasurement();

  void setBaseInfluxMeasurement(String influxDBMeasurement);
}
