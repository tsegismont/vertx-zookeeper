package io.vertx.spi.cluster.zookeeper.impl;

import io.vertx.core.Completable;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.VertxException;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.internal.VertxInternal;
import io.vertx.core.internal.logging.Logger;
import io.vertx.core.internal.logging.LoggerFactory;
import io.vertx.core.spi.cluster.RegistrationInfo;
import io.vertx.core.spi.cluster.RegistrationListener;
import io.vertx.core.spi.cluster.RegistrationUpdateEvent;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.CuratorEventType;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.TreeCache;
import org.apache.curator.framework.recipes.cache.TreeCacheEvent;
import org.apache.curator.framework.recipes.cache.TreeCacheListener;
import org.apache.zookeeper.CreateMode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import java.util.function.Function;

public class SubsMapHelper implements TreeCacheListener {

  private final CuratorFramework curator;
  private final TreeCache treeCache;
  private final VertxInternal vertx;
  private final RegistrationListener registrationListener;
  private final String nodeId;
  private final ConcurrentMap<String, Set<RegistrationInfo>> ownSubs = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Set<RegistrationInfo>> localSubs = new ConcurrentHashMap<>();

  private static final String VERTX_SUBS_NAME = "/__vertx.subs";
  private static final Logger log = LoggerFactory.getLogger(SubsMapHelper.class);

  private static final Function<String, String> keyPath = address -> VERTX_SUBS_NAME + "/" + address;
  private static final Function<RegistrationInfo, String> valuePath = registrationInfo -> registrationInfo.nodeId() + "-" + registrationInfo.seq();
  private static final BiFunction<String, RegistrationInfo, String> fullPath = (address, registrationInfo) -> keyPath.apply(address) + "/" + valuePath.apply(registrationInfo);

  public SubsMapHelper(CuratorFramework curator, VertxInternal vertx, RegistrationListener registrationListener, String nodeId) {
    this.curator = curator;
    this.vertx = vertx;
    this.treeCache = new TreeCache(curator, VERTX_SUBS_NAME);
    this.treeCache.getListenable().addListener(this);
    try {
      this.treeCache.start();
    } catch (Exception e) {
      throw new VertxException(e);
    }
    this.registrationListener = registrationListener;
    this.nodeId = nodeId;
  }

  public void close() {
    treeCache.close();
  }

  public void put(String address, RegistrationInfo registrationInfo, Completable<Void> promise) {
    if (registrationInfo.localOnly()) {
      localSubs.compute(address, (add, curr) -> addToSet(registrationInfo, curr));
      fireRegistrationUpdateEvent(address);
      promise.succeed();
    } else {
      try {
        Buffer buffer = Buffer.buffer();
        registrationInfo.writeToBuffer(buffer);
        curator.create().orSetData().creatingParentContainersIfNeeded().withMode(CreateMode.EPHEMERAL).inBackground((c, e) -> {
          if (e.getType() == CuratorEventType.CREATE || e.getType() == CuratorEventType.SET_DATA) {
            vertx.runOnContext(Avoid -> {
              ownSubs.compute(address, (add, curr) -> addToSet(registrationInfo, curr));
              promise.succeed();
            });
          }
        }).withUnhandledErrorListener(log::error).forPath(fullPath.apply(address, registrationInfo), buffer.getBytes());
      } catch (Exception e) {
        log.error(String.format("create subs address %s failed.", address), e);
      }
    }
  }

  private Set<RegistrationInfo> addToSet(RegistrationInfo registrationInfo, Set<RegistrationInfo> curr) {
    Set<RegistrationInfo> res = curr != null ? curr : Collections.synchronizedSet(new LinkedHashSet<>());
    res.add(registrationInfo);
    return res;
  }

  public List<RegistrationInfo> get(String address) {
    Map<String, ChildData> map = treeCache.getCurrentChildren(keyPath.apply(address));
    Collection<ChildData> remote = (map == null) ? Collections.emptyList() : map.values();

    List<RegistrationInfo> list;
    int size;
    size = remote.size();
    Set<RegistrationInfo> local = localSubs.get(address);
    if (local != null) {
      synchronized (local) {
        size += local.size();
        if (size == 0) {
          return Collections.emptyList();
        }
        list = new ArrayList<>(size);
        list.addAll(local);
      }
    } else if (size == 0) {
      return Collections.emptyList();
    } else {
      list = new ArrayList<>(size);
    }
    for (ChildData childData : remote) {
      list.add(toRegistrationInfo(childData));
    }
    return list;
  }

  private static RegistrationInfo toRegistrationInfo(ChildData childData) {
    RegistrationInfo registrationInfo = new RegistrationInfo();
    Buffer buffer = Buffer.buffer(childData.getData());
    registrationInfo.readFromBuffer(0, buffer);
    return registrationInfo;
  }

  public void remove(String address, RegistrationInfo registrationInfo, Completable<Void> promise) {
    try {
      if (registrationInfo.localOnly()) {
        localSubs.computeIfPresent(address, (add, curr) -> removeFromSet(registrationInfo, curr));
        fireRegistrationUpdateEvent(address);
        promise.succeed();
      } else {
        curator.delete().guaranteed().inBackground((c, e) -> {
          if (e.getType() == CuratorEventType.DELETE) {
            vertx.runOnContext(aVoid -> {
              ownSubs.computeIfPresent(address, (add, curr) -> removeFromSet(registrationInfo, curr));
              promise.succeed();
            });
          }
        }).forPath(fullPath.apply(address, registrationInfo));
      }
    } catch (Exception e) {
      log.error(String.format("remove subs address %s failed.", address), e);
      promise.fail(e);
    }
  }

  private Set<RegistrationInfo> removeFromSet(RegistrationInfo registrationInfo, Set<RegistrationInfo> curr) {
    curr.remove(registrationInfo);
    return curr.isEmpty() ? null : curr;
  }

  @Override
  public void childEvent(CuratorFramework client, TreeCacheEvent event) {
    switch (event.getType()) {
      case NODE_ADDED:
      case NODE_UPDATED:
      case NODE_REMOVED:
        // /__vertx.subs/AddressName/NodeId -> AddressName
        String[] pathElements = event.getData().getPath().split("/", 4);
        if (pathElements.length <= 3) {
          // "/__vertx.subs" and "/__vertx.subs/XX" added event
          break;
        }
        String addr = pathElements[2];
        vertx.<List<RegistrationInfo>>executeBlocking(() -> get(addr), false).onComplete(ar -> {
          if (ar.succeeded()) {
            registrationListener.registrationsUpdated(new RegistrationUpdateEvent(addr, ar.result()));
          } else {
            log.trace("A failure occured while retrieving the updated registrations", ar.cause());
            registrationListener.registrationsUpdated(new RegistrationUpdateEvent(addr, Collections.emptyList()));
          }
        });
        break;
      case CONNECTION_SUSPENDED:
        log.warn(String.format("vertx node %s which connected to zookeeper have been suspended.", nodeId));
        break;
      case CONNECTION_LOST:
        log.warn(String.format("vertx node %s which connected to zookeeper has lost", nodeId));
        break;
      case CONNECTION_RECONNECTED:
        log.info(String.format("vertx node %s have reconnected to zookeeper", nodeId));
        vertx.runOnContext(aVoid -> {
          List<Future<Void>> futures = new ArrayList<>();
          for (Map.Entry<String, Set<RegistrationInfo>> entry : ownSubs.entrySet()) {
            for (RegistrationInfo registrationInfo : entry.getValue()) {
              Promise<Void> promise = Promise.promise();
              put(entry.getKey(), registrationInfo, promise);
              futures.add(promise.future());
            }
          }
          Future.all(futures).onComplete(ar -> {
            if (ar.failed()) {
              log.error("recover node subs information failed.", ar.cause());
            } else {
              log.info("recover node subs success.");
            }
          });
        });
        break;
    }
  }

  private void fireRegistrationUpdateEvent(String address) {
    registrationListener.registrationsUpdated(new RegistrationUpdateEvent(address, get(address)));
  }

}
