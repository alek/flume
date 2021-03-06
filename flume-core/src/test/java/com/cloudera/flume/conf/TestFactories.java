/**
 * Licensed to Cloudera, Inc. under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Cloudera, Inc. licenses this file
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
package com.cloudera.flume.conf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.flume.ExampleData;
import com.cloudera.flume.core.Driver.DriverState;
import com.cloudera.flume.core.Event;
import com.cloudera.flume.core.EventImpl;
import com.cloudera.flume.core.EventSink;
import com.cloudera.flume.core.EventSinkDecorator;
import com.cloudera.flume.core.EventSource;
import com.cloudera.flume.core.EventUtil;
import com.cloudera.flume.core.connector.DirectDriver;
import com.cloudera.flume.handlers.avro.AvroEventSink;
import com.cloudera.flume.handlers.avro.AvroEventSource;
import com.cloudera.flume.reporter.aggregator.CounterSink;
import com.cloudera.util.Clock;

/**
 * This test sink and source generating factories.
 */
public class TestFactories implements ExampleData {
  public static SinkFactory fact = new SinkFactoryImpl();
  public static SourceFactory srcfact = new SourceFactoryImpl();
  public static final Logger LOG = LoggerFactory.getLogger(TestFactories.class);
  final static int LINES = 25;

  @Test
  public void testBuildConsole() throws IOException, FlumeSpecException,
      InterruptedException {
    EventSink snk = fact.createSink(new Context(), "console", new Object[0]);
    snk.open();
    snk.append(new EventImpl("test".getBytes()));
    snk.close();
  }

  @Test
  public void testBuildTextSource() throws IOException, FlumeSpecException,
      InterruptedException {
    Context ctx = LogicalNodeContext.testingContext();

    // 25 lines of 100 bytes of ascii
    EventSource src = srcfact.createSource(ctx, "asciisynth", 25, 100);
    src.open();
    Event e = null;
    int cnt = 0;
    while ((e = src.next()) != null) {
      System.out.println(e);
      cnt++;
    }
    src.close();
    assertEquals(LINES, cnt);
  }

  @Test
  public void testConnector() throws IOException, InterruptedException,
      FlumeSpecException {
    Context ctx = LogicalNodeContext.testingContext();

    EventSink snk = fact.createSink(ctx, "console", new Object[0]);
    snk.open();

    EventSource src = srcfact.createSource(ctx, "asciisynth", 25, 100);
    src.open();

    DirectDriver conn = new DirectDriver(src, snk);
    conn.start();

    conn.join(Long.MAX_VALUE);

    snk.close();
    src.close();
  }

  @Test
  public void testDecorator() throws IOException, FlumeSpecException,
      InterruptedException {
    Context ctx = LogicalNodeContext.testingContext();
    EventSource src = srcfact.createSource(ctx, "asciisynth", 25, 100);
    src.open();

    EventSinkDecorator<EventSink> deco =

    fact.createDecorator(new Context(), "intervalSampler", 5);
    EventSink snk = fact.createSink(new Context(), "counter", "name");

    snk.open();

    deco.setSink(snk);
    EventUtil.dumpAll(src, snk);
  }

  /**
   * This tests that right type (Avro/Thrift) source sink is created based on
   * the value of EVENT_RPC_TYPE set in Configuration file.
   */
  @Test
  public void testRpcSourceSinksInit() throws FlumeSpecException {
    FlumeConfiguration.get().set(FlumeConfiguration.EVENT_RPC_TYPE, "garbage");
    Context ctx = LogicalNodeContext.testingContext();

    // making sure default is Thrift
    EventSource rpcSrc = srcfact.createSource(ctx, "rpcSource", 31337);
    EventSink rpcSink = fact
        .createSink(new Context(), "rpcSink", "0.0.0.0", 31337);

    // make sure initializing to Thrift indeed gives us ThriftEvent sources and
    // sinks.
    FlumeConfiguration.get().set(FlumeConfiguration.EVENT_RPC_TYPE, "THRIFT");
    rpcSrc = srcfact.createSource(ctx, "rpcSource", 31337);
    rpcSink = fact.createSink(new Context(), "rpcSink", "0.0.0.0", 31337);

    // make sure initializing to Avro indeed gives us AvroEvent sources and
    // sinks.
    FlumeConfiguration.get().set(FlumeConfiguration.EVENT_RPC_TYPE, "AVRO");
    rpcSrc = srcfact.createSource(ctx, "rpcSource", 31337);
    rpcSink = fact.createSink(new Context(), "rpcSink", "0.0.0.0", 31337);
    assertEquals(AvroEventSource.class, rpcSrc.getClass());
    assertEquals(AvroEventSink.class, rpcSink.getClass());
  }

  /**
   * This tests RpcSnk/Source for both Avro and Thrift type.
   */
  @Test
  public void testRpcSourceSinks() throws IOException, InterruptedException,
      FlumeSpecException {
    testRpc(FlumeConfiguration.RPC_TYPE_THRIFT);
    testRpc(FlumeConfiguration.RPC_TYPE_AVRO);
  }

  /*
   * Following comment is carried from the ThriftRpcTest:
   * 
   * This seems to fail about 1 out of 10 times. There is a timing issues due to
   * the multi-threading.
   */
  private void testRpc(String rpcType) throws IOException,
      InterruptedException, FlumeSpecException {
    FlumeConfiguration.get().set(FlumeConfiguration.EVENT_RPC_TYPE, rpcType);
    LOG.info("Testing a more complicated pipeline with a " + rpcType
        + " network connection in the middle");
    Context ctx = LogicalNodeContext.testingContext();
    EventSource rpcSrc = srcfact.createSource(ctx, "rpcSource", 31337);
    EventSink rpcSink = fact.createSink(ctx, "rpcSink", "0.0.0.0", 31337);

    EventSink counter = fact.createSink(ctx, "counter", (Object) "count");
    EventSource txtsrc = srcfact.createSource(ctx, "asciisynth", 25, 100);

    DirectDriver svrconn = new DirectDriver(rpcSrc, counter);
    svrconn.start();
    assertTrue("rpc server took too long to start",
        svrconn.waitForAtLeastState(DriverState.ACTIVE, 1000));

    // start and send the data
    DirectDriver cliconn = new DirectDriver(txtsrc, rpcSink);
    cliconn.start();
    // the avro version sometimes takes a while to start jetty
    assertTrue("rpc client took too long to connect",
        cliconn.waitForAtLeastState(DriverState.ACTIVE, 10000));
    assertTrue("rpc client took too long to close cleanly",
        cliconn.waitForAtLeastState(DriverState.IDLE, 2500));
    Clock.sleep(2000); // data could be stuck in tcp buffer

    svrconn.stop();
    rpcSrc.close(); // force rpc close to make sure next test does not port
                    // conflict.

    assertTrue("rpc server took too long to close cleanly",
        svrconn.waitForAtLeastState(DriverState.IDLE, 2000));

    LOG.info("read " + ((CounterSink) counter).getCount() + " lines");
    assertEquals(LINES, ((CounterSink) counter).getCount());
    assertNull(cliconn.getException());
    assertNull(svrconn.getException());
  }
}
