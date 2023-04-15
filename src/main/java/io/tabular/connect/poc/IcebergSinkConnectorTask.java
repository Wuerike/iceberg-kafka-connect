// Copyright 2023 Tabular Technologies Inc.
package io.tabular.connect.poc;

import io.tabular.connect.poc.commit.Coordinator;
import io.tabular.connect.poc.commit.Worker;
import java.util.Collection;
import java.util.Map;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.util.PropertyUtil;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;

public class IcebergSinkConnectorTask extends SinkTask {

  private Catalog catalog;
  private TableIdentifier tableIdentifier;
  private Map<String, String> props;
  private IcebergWriter writer;
  private Coordinator coordinator;
  private Worker worker;

  private static final String TABLE_PROP = "iceberg.table";
  private static final String COMMIT_INTERVAL_MS_PROP = "iceberg.table.commitIntervalMs";
  private static final int COMMIT_INTERVAL_MS_DEFAULT = 60_000;

  @Override
  public String version() {
    return "0.0.1";
  }

  @Override
  public void start(Map<String, String> props) {
    this.props = props;
    this.catalog = IcebergUtil.loadCatalog(props);
    this.tableIdentifier = TableIdentifier.parse(props.get(TABLE_PROP));

    // TODO: handle leader election when there are multiple topics
    if (context.assignment().stream().anyMatch(tp -> tp.partition() == 0)) {
      coordinator = new Coordinator(props);
    }
    worker = new Worker(props);
  }

  @Override
  public void put(Collection<SinkRecord> sinkRecords) {
    if (writer == null) {
      writer = createWriter();
    }
    writer.write(sinkRecords);
  }

  @Override
  public void flush(Map<TopicPartition, OffsetAndMetadata> currentOffsets) {
    if (writer != null) {
      writer.commitIfNeeded();
    }
  }

  @Override
  public void stop() {
    if (writer != null) {
      writer.close();
    }
  }

  private IcebergWriter createWriter() {
    return new IcebergWriter(
        catalog,
        tableIdentifier,
        PropertyUtil.propertyAsInt(props, COMMIT_INTERVAL_MS_PROP, COMMIT_INTERVAL_MS_DEFAULT));
  }
}
