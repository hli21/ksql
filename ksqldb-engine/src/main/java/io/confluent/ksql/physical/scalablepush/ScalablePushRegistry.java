/*
 * Copyright 2021 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.physical.scalablepush;

import com.google.common.annotations.VisibleForTesting;
import io.confluent.ksql.GenericKey;
import io.confluent.ksql.GenericRow;
import io.confluent.ksql.Window;
import io.confluent.ksql.physical.common.QueryRow;
import io.confluent.ksql.physical.common.QueryRowImpl;
import io.confluent.ksql.physical.scalablepush.locator.AllHostsLocator;
import io.confluent.ksql.physical.scalablepush.locator.PushLocator;
import io.confluent.ksql.query.QueryId;
import io.confluent.ksql.schema.ksql.LogicalSchema;
import io.confluent.ksql.util.PersistentQueryMetadata;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.ProcessorSupplier;
import org.apache.kafka.streams.processor.api.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This registry is kept with every persistent query, peeking at the stream which is the output
 * of the topology. These rows are then fed to any registered ProcessingQueues where they are
 * eventually passed on to scalable push queries.
 */
public class ScalablePushRegistry implements ProcessorSupplier<Object, GenericRow, Void, Void> {

  private static final Logger LOG = LoggerFactory.getLogger(ScalablePushRegistry.class);

  private final PushLocator pushLocator;
  private final LogicalSchema logicalSchema;
  private final boolean isTable;
  private final boolean windowed;
  private final boolean newNodeContinuityEnforced;
  // All mutable field accesses are protected with synchronized.  The exception is when
  // processingQueues is accessed to processed rows, in which case we want a weakly consistent
  // view of the map, so we just iterate over the ConcurrentHashMap directly.
  private final ConcurrentHashMap<QueryId, ProcessingQueue> processingQueues
      = new ConcurrentHashMap<>();
  private boolean closed = false;
  private volatile boolean hasReceivedData = false;

  public ScalablePushRegistry(
      final PushLocator pushLocator,
      final LogicalSchema logicalSchema,
      final boolean isTable,
      final boolean windowed,
      final boolean newNodeContinuityEnforced
  ) {
    this.pushLocator = pushLocator;
    this.logicalSchema = logicalSchema;
    this.isTable = isTable;
    this.windowed = windowed;
    this.newNodeContinuityEnforced = newNodeContinuityEnforced;
  }

  public synchronized void close() {
    for (ProcessingQueue queue : processingQueues.values()) {
      queue.close();
    }
    processingQueues.clear();
    closed = true;
  }

  public synchronized void register(
      final ProcessingQueue processingQueue,
      final boolean expectingStartOfRegistryData
  ) {
    if (closed) {
      throw new IllegalStateException("Shouldn't register after closing");
    }
    if (hasReceivedData && newNodeContinuityEnforced && expectingStartOfRegistryData) {
      throw new IllegalStateException("New node missed data");
    }
    processingQueues.put(processingQueue.getQueryId(), processingQueue);
  }

  public synchronized void unregister(final ProcessingQueue processingQueue) {
    if (closed) {
      throw new IllegalStateException("Shouldn't unregister after closing");
    }
    processingQueues.remove(processingQueue.getQueryId());
  }

  public PushLocator getLocator() {
    return pushLocator;
  }

  public boolean isTable() {
    return isTable;
  }

  public boolean isWindowed() {
    return windowed;
  }


  @VisibleForTesting
  public int numRegistered() {
    return processingQueues.size();
  }

  @SuppressWarnings("unchecked")
  private void handleRow(final Record<Object, GenericRow> record) {
    hasReceivedData = true;
    final Object key = record.key();
    final GenericRow value = record.value();

    // We don't currently handle null in either field
    if ((key == null && !logicalSchema.key().isEmpty()) || value == null) {
      return;
    }
    for (ProcessingQueue queue : processingQueues.values()) {
      final long timestamp = record.timestamp();

      try {
        // The physical operators may modify the keys and values, so we make a copy to ensure
        // that there's no cross-query interference.
        final QueryRow row;
        if (!windowed) {
          final GenericKey keyCopy = GenericKey.fromList(
              key != null ? ((GenericKey) key).values() : Collections.emptyList());
          final GenericRow valueCopy = GenericRow.fromList(value.values());
          row = QueryRowImpl.of(logicalSchema, keyCopy, Optional.empty(), valueCopy, timestamp);
        } else {
          final Windowed<GenericKey> windowedKey = (Windowed<GenericKey>) key;
          final GenericKey keyCopy = GenericKey.fromList(windowedKey.key().values());
          final GenericRow valueCopy = GenericRow.fromList(value.values());
          row = QueryRowImpl.of(logicalSchema, keyCopy, Optional.of(Window.of(
              windowedKey.window().startTime(),
              windowedKey.window().endTime()
          )), valueCopy, timestamp);
        }
        queue.offer(row);
      } catch (final Throwable t) {
        LOG.error("Error while offering row", t);
      }
    }
  }

  public synchronized void onError() {
    for (ProcessingQueue queue : processingQueues.values()) {
      queue.onError();
    }
  }

  @Override
  public Processor<Object, GenericRow, Void, Void> get() {
    return new PeekProcessor();
  }

  private final class PeekProcessor implements Processor<Object, GenericRow, Void, Void> {

    private PeekProcessor() {

    }

    public void init(final ProcessorContext context) {

    }

    public void process(final Record<Object, GenericRow> record) {
      handleRow(record);
    }

    @Override
    public void close() {

    }
  }

  public static Optional<ScalablePushRegistry> create(
      final LogicalSchema logicalSchema,
      final Supplier<List<PersistentQueryMetadata>> allPersistentQueries,
      final boolean isTable,
      final boolean windowed,
      final Map<String, Object> streamsProperties,
      final boolean newNodeContinuityEnforced
  ) {
    final Object appServer = streamsProperties.get(StreamsConfig.APPLICATION_SERVER_CONFIG);
    if (appServer == null) {
      return Optional.empty();
    }

    if (!(appServer instanceof String)) {
      throw new IllegalArgumentException(StreamsConfig.APPLICATION_SERVER_CONFIG + " not String");
    }

    final URL localhost;
    try {
      localhost = new URL((String) appServer);
    } catch (final MalformedURLException e) {
      throw new IllegalArgumentException(StreamsConfig.APPLICATION_SERVER_CONFIG + " malformed: "
          + "'" + appServer + "'");
    }

    final PushLocator pushLocator = new AllHostsLocator(allPersistentQueries, localhost);
    return Optional.of(new ScalablePushRegistry(pushLocator, logicalSchema, isTable, windowed,
        newNodeContinuityEnforced));
  }
}
