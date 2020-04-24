/*
 * Copyright (c) 2011-2019 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */

package io.vertx.test.fakecluster;

import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.shareddata.AsyncMap;
import io.vertx.core.shareddata.Counter;
import io.vertx.core.shareddata.Lock;
import io.vertx.core.shareddata.impl.AsynchronousCounter;
import io.vertx.core.shareddata.impl.LocalAsyncLocks;
import io.vertx.core.shareddata.impl.LocalAsyncMapImpl;
import io.vertx.core.spi.cluster.*;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@SuppressWarnings({"unchecked", "rawtypes"})
public class FakeClusterManager implements ClusterManager {

  private static final Map<String, FakeClusterManager> nodes = Collections.synchronizedMap(new LinkedHashMap<>());

  private static final ConcurrentMap<String, List<RegistrationInfo>> registrations = new ConcurrentHashMap<>();
  private static final ConcurrentMap<String, NodeInfo> nodeInfos = new ConcurrentHashMap<>();

  private static final ConcurrentMap<String, LocalAsyncMapImpl> asyncMaps = new ConcurrentHashMap<>();
  private static final ConcurrentMap<String, Map> syncMaps = new ConcurrentHashMap<>();
  private static LocalAsyncLocks localAsyncLocks = new LocalAsyncLocks();
  private static final ConcurrentMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

  private String nodeID;
  private NodeListener nodeListener;
  private VertxInternal vertx;

  public void setVertx(VertxInternal vertx) {
    this.vertx = vertx;
  }

  private static void doJoin(String nodeID, FakeClusterManager node) {
    if (nodes.containsKey(nodeID)) {
      throw new IllegalStateException("Node has already joined!");
    }
    nodes.put(nodeID, node);
    synchronized (nodes) {
      for (Entry<String, FakeClusterManager> entry : nodes.entrySet()) {
        if (!entry.getKey().equals(nodeID)) {
          new Thread(() -> entry.getValue().memberAdded(nodeID)).start();
        }
      }
    }
  }

  private synchronized void memberAdded(String nodeID) {
    if (isActive()) {
      try {
        if (nodeListener != null) {
          nodeListener.nodeAdded(nodeID);
        }
      } catch (Throwable t) {
        t.printStackTrace();
      }
    }
  }

  private static void doLeave(String nodeID) {
    nodes.remove(nodeID);
    synchronized (nodes) {
      for (Entry<String, FakeClusterManager> entry : nodes.entrySet()) {
        if (!entry.getKey().equals(nodeID)) {
          new Thread(() -> entry.getValue().memberRemoved(nodeID)).start();
        }
      }
    }
  }

  private synchronized void memberRemoved(String nodeID) {
    if (isActive()) {
      try {
        if (nodeListener != null) {
          nodeListener.nodeLeft(nodeID);
        }
      } catch (Throwable t) {
        t.printStackTrace();
      }
    }
  }

  @Override
  public <K, V> void getAsyncMap(String name, Promise<AsyncMap<K, V>> promise) {
    promise.complete(asyncMaps.computeIfAbsent(name, n -> new LocalAsyncMapImpl(vertx)));
  }

  @Override
  public <K, V> Map<K, V> getSyncMap(String name) {
    Map map = syncMaps.get(name);
    if (map == null) {
      map = new ConcurrentHashMap<>();
      Map prevMap = syncMaps.putIfAbsent(name, map);
      if (prevMap != null) {
        map = prevMap;
      }
    }
    Map<K, V> theMap = map;
    return theMap;
  }

  @Override
  public void getLockWithTimeout(String name, long timeout, Promise<Lock> promise) {
    localAsyncLocks.acquire(vertx.getOrCreateContext(), name, timeout).onComplete(promise);
  }

  @Override
  public void getCounter(String name, Promise<Counter> promise) {
    promise.complete(new AsynchronousCounter(vertx, counters.computeIfAbsent(name, k -> new AtomicLong())));
  }

  @Override
  public String getNodeId() {
    return nodeID;
  }

  @Override
  public List<String> getNodes() {
    ArrayList<String> res;
    synchronized (nodes) {
      res = new ArrayList<>(nodes.keySet());
    }
    return res;
  }

  @Override
  public void nodeListener(NodeListener listener) {
    this.nodeListener = listener;
  }

  @Override
  public void setNodeInfo(NodeInfo nodeInfo, Promise<Void> promise) {
    nodeInfos.put(nodeID, nodeInfo);
    promise.complete();
  }

  @Override
  public NodeInfo getNodeInfo() {
    return nodeInfos.get(nodeID);
  }

  @Override
  public void getNodeInfo(String nodeId, Promise<NodeInfo> promise) {
    NodeInfo result = nodeInfos.get(nodeId);
    if (result != null) {
      promise.complete(result);
    } else {
      promise.fail("Not a member of the cluster");
    }
  }

  @Override
  public void join(Promise<Void> promise) {
    vertx.executeBlocking(fut -> {
      synchronized (this) {
        this.nodeID = UUID.randomUUID().toString();
        doJoin(nodeID, this);
      }
      fut.complete();
    }, promise);
  }

  @Override
  public void leave(Promise<Void> promise) {
    registrations.forEach((address, registrationInfos) -> {
      synchronized (registrationInfos) {
        registrationInfos.removeIf(registrationInfo -> registrationInfo.getNodeId().equals(nodeID));
      }
    });
    vertx.executeBlocking(fut -> {
      synchronized (this) {
        if (nodeID != null) {
          nodeInfos.remove(nodeID);
          if (nodeListener != null) {
            nodeListener = null;
          }
          doLeave(nodeID);
          this.nodeID = null;
        }
      }
      fut.complete();
    }, promise);
  }

  @Override
  public boolean isActive() {
    return nodeID != null;
  }

  @Override
  public void register(String address, RegistrationInfo registrationInfo, Promise<Void> promise) {
    registrations.computeIfAbsent(address, k -> Collections.synchronizedList(new ArrayList<>()))
      .add(registrationInfo);
    promise.complete();
  }

  @Override
  public void unregister(String address, RegistrationInfo registrationInfo, Promise<Void> promise) {
    List<RegistrationInfo> infos = registrations.get(address);
    if (infos != null && infos.remove(registrationInfo)) {
      promise.complete();
    } else {
      promise.fail("Registration not found");
    }
  }

  @Override
  public void registrationListener(String address, Promise<RegistrationListener> promise) {
    promise.complete(new FakeRegistrationListener(address));
  }

  public static void reset() {
    registrations.clear();
    nodes.clear();
    asyncMaps.clear();
    localAsyncLocks = new LocalAsyncLocks();
    counters.clear();
    syncMaps.clear();
  }

  private static class FakeRegistrationListener implements RegistrationListener {

    static final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(
      Runtime.getRuntime().availableProcessors(),
      r -> {
        Thread thread = new Thread(r);
        thread.setDaemon(true);
        return thread;
      });

    private final String address;

    private List<RegistrationInfo> initial, last;
    private Handler<List<RegistrationInfo>> handler;
    private Handler<Void> endHandler;
    private ScheduledFuture<?> scheduledFuture;

    FakeRegistrationListener(String address) {
      this.address = address;
      initial = getRegistrationInfos();
    }

    private List<RegistrationInfo> getRegistrationInfos() {
      List<RegistrationInfo> registrationInfos = registrations.get(address);
      if (registrationInfos == null || registrationInfos.isEmpty()) {
        return Collections.emptyList();
      }
      synchronized (registrationInfos) {
        return Collections.unmodifiableList(new ArrayList<>(registrationInfos));
      }
    }

    @Override
    public List<RegistrationInfo> initialState() {
      return initial;
    }

    @Override
    public RegistrationListener exceptionHandler(Handler<Throwable> handler) {
      return this;
    }

    @Override
    public synchronized RegistrationListener handler(Handler<List<RegistrationInfo>> handler) {
      this.handler = handler;
      return this;
    }

    @Override
    public synchronized RegistrationListener endHandler(Handler<Void> endHandler) {
      this.endHandler = endHandler;
      return this;
    }

    @Override
    public synchronized void start() {
      scheduledFuture = executorService.scheduleWithFixedDelay(this::checkUpdate, 5, 5, TimeUnit.MILLISECONDS);
    }

    private void checkUpdate() {
      List<RegistrationInfo> infos = getRegistrationInfos();
      Runnable emission;
      synchronized (this) {
        if (initial != null) {
          if (infos.isEmpty()) {
            emission = terminalEvent();
          } else if (!initial.equals(infos)) {
            emission = itemEvent(infos);
          } else {
            emission = null;
          }
          last = infos;
          initial = null;
        } else if (last.isEmpty() || last.equals(infos)) {
          emission = null;
        } else {
          last = infos;
          if (last.isEmpty()) {
            emission = terminalEvent();
          } else {
            emission = itemEvent(infos);
          }
        }
      }
      if (emission != null) {
        emission.run();
      }
    }

    private synchronized Runnable itemEvent(List<RegistrationInfo> infos) {
      Handler<List<RegistrationInfo>> h = handler;
      return () -> {
        if (h != null) {
          h.handle(infos);
        }
      };
    }

    private synchronized Runnable terminalEvent() {
      Handler<Void> e = endHandler;
      return () -> {
        if (e != null) {
          e.handle(null);
        }
        stop();
      };
    }

    @Override
    public synchronized void stop() {
      if (scheduledFuture != null) {
        scheduledFuture.cancel(false);
      }
    }
  }
}
