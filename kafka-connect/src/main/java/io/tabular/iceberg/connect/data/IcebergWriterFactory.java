/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.tabular.iceberg.connect.data;

import io.tabular.iceberg.connect.IcebergSinkConfig;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.relocated.com.google.common.annotations.VisibleForTesting;
import org.apache.iceberg.types.Types.StructType;
import org.apache.iceberg.util.Tasks;
import org.apache.kafka.connect.sink.SinkRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IcebergWriterFactory {

  private static final Logger LOG = LoggerFactory.getLogger(IcebergWriterFactory.class);

  private final Catalog catalog;
  private final IcebergSinkConfig config;

  public IcebergWriterFactory(Catalog catalog, IcebergSinkConfig config) {
    this.catalog = catalog;
    this.config = config;
  }

  public RecordWriter createWriter(
      String tableName, SinkRecord sample, boolean ignoreMissingTable) {
    TableIdentifier identifier = TableIdentifier.parse(tableName);
    Table table;
    try {
      table = catalog.loadTable(identifier);
    } catch (NoSuchTableException nst) {
      if (config.autoCreateEnabled()) {
        table = autoCreateTable(tableName, sample);
      } else if (ignoreMissingTable) {
        return new RecordWriter() {};
      } else {
        throw nst;
      }
    }

    return new IcebergWriter(table, tableName, config);
  }

  @VisibleForTesting
  Table autoCreateTable(String tableName, SinkRecord sample) {
    StructType structType;
    if (sample.valueSchema() == null) {
      structType = SchemaUtils.inferIcebergType(sample.value()).asStructType();
    } else {
      structType = SchemaUtils.toIcebergType(sample.valueSchema()).asStructType();
    }

    org.apache.iceberg.Schema schema = new org.apache.iceberg.Schema(structType.fields());
    TableIdentifier identifier = TableIdentifier.parse(tableName);

    List<String> partitionBy = config.tableConfig(tableName).partitionBy();
    PartitionSpec spec;
    try {
      spec = SchemaUtils.createPartitionSpec(schema, partitionBy);
    } catch (Exception e) {
      LOG.error(
          "Unable to create partition spec {}, table {} will be unpartitioned",
          partitionBy,
          identifier,
          e);
      spec = PartitionSpec.unpartitioned();
    }

    PartitionSpec partitionSpec = spec;
    AtomicReference<Table> result = new AtomicReference<>();
    Tasks.range(1)
        .retry(IcebergSinkConfig.CREATE_TABLE_RETRIES)
        .run(
            notUsed -> {
              try {
                result.set(catalog.loadTable(identifier));
              } catch (NoSuchTableException e) {
                result.set(
                    catalog.createTable(
                        identifier, schema, partitionSpec, config.autoCreateProps()));
              }
            });
    return result.get();
  }
}
