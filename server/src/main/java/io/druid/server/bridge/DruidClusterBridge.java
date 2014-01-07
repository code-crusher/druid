package io.druid.server.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Predicate;
import com.google.common.base.Throwables;
import com.google.common.collect.Maps;
import com.google.common.io.Closeables;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Inject;
import com.metamx.common.concurrent.ScheduledExecutorFactory;
import com.metamx.common.concurrent.ScheduledExecutors;
import com.metamx.common.guava.FunctionalIterable;
import com.metamx.common.lifecycle.LifecycleStart;
import com.metamx.common.lifecycle.LifecycleStop;
import com.metamx.emitter.EmittingLogger;
import io.druid.client.DruidServer;
import io.druid.client.ServerInventoryView;
import io.druid.client.ServerView;
import io.druid.concurrent.Execs;
import io.druid.curator.announcement.Announcer;
import io.druid.guice.ManageLifecycle;
import io.druid.guice.annotations.Self;
import io.druid.server.DruidNode;
import io.druid.server.coordination.AbstractDataSegmentAnnouncer;
import io.druid.server.coordination.DruidServerMetadata;
import io.druid.timeline.DataSegment;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.leader.LeaderLatch;
import org.apache.curator.framework.recipes.leader.LeaderLatchListener;
import org.apache.curator.utils.ZKPaths;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

/**
 */
@ManageLifecycle
public class DruidClusterBridge
{
  public static final String CONNECTOR_OWNER_NODE = "_CONNECTOR";
  public static final String NODE_TYPE = "bridge";

  private static final EmittingLogger log = new EmittingLogger(DruidClusterBridge.class);

  private final ObjectMapper jsonMapper;
  private final DruidClusterBridgeConfig config;
  private final BridgeZkCoordinator bridgeZkCoordinator; // watches for assignments from main cluster
  private final Announcer announcer; //announce self to main cluster
  private final ServerInventoryView<Object> serverInventoryView;
  private final CuratorFramework curator;
  private final ScheduledExecutorService exec;
  private final AtomicReference<LeaderLatch> leaderLatch;
  private final DruidNode self;

  private final Map<DataSegment, Integer> segments = Maps.newHashMap();
  private final Object lock = new Object();

  private volatile boolean started = false;
  private volatile boolean leader = false;

  @Inject
  public DruidClusterBridge(
      ObjectMapper jsonMapper,
      DruidClusterBridgeConfig config,
      BridgeZkCoordinator bridgeZkCoordinator,
      @Bridge Announcer announcer,
      @Bridge final AbstractDataSegmentAnnouncer dataSegmentAnnouncer,
      ServerInventoryView serverInventoryView,
      CuratorFramework curator,
      ScheduledExecutorFactory scheduledExecutorFactory,
      AtomicReference<LeaderLatch> leaderLatch,
      @Self DruidNode self
  )
  {
    this.jsonMapper = jsonMapper;
    this.config = config;
    this.bridgeZkCoordinator = bridgeZkCoordinator;
    this.announcer = announcer;
    this.serverInventoryView = serverInventoryView;
    this.curator = curator;
    this.leaderLatch = leaderLatch;

    this.exec = scheduledExecutorFactory.create(1, "Coordinator-Exec--%d");
    this.self = self;

    log.info("Local curator: [%s]", curator); // TODO

    serverInventoryView.registerSegmentCallback(
        Executors.newFixedThreadPool(
            1,
            new ThreadFactoryBuilder().setDaemon(true)
                                      .setNameFormat("DruidClusterBridge-ServerInventoryView-%d")
                                      .build()
        ),
        new ServerView.BaseSegmentCallback()
        {
          @Override
          public ServerView.CallbackAction segmentAdded(
              DruidServer server, DataSegment segment
          )
          {
            try {
              synchronized (lock) {
                Integer count = segments.get(segment);
                if (count == null) {
                  segments.put(segment, 1);
                  dataSegmentAnnouncer.announceSegment(segment);
                } else {
                  segments.put(segment, count + 1);
                }
              }
            }
            catch (Exception e) {
              throw Throwables.propagate(e);
            }

            return ServerView.CallbackAction.CONTINUE;
          }

          @Override
          public ServerView.CallbackAction segmentRemoved(DruidServer server, DataSegment segment)
          {
            try {
              synchronized (lock) {
                Integer count = segments.get(segment);
                if (count != null) {
                  if (count == 0) {
                    dataSegmentAnnouncer.unannounceSegment(segment);
                    segments.remove(segment);
                  } else {
                    segments.put(segment, count - 1);
                  }
                }
              }
            }
            catch (Exception e) {
              throw Throwables.propagate(e);
            }

            return ServerView.CallbackAction.CONTINUE;
          }
        }
    );
  }

  public boolean isLeader()
  {
    return leader;
  }

  @LifecycleStart
  public void start()
  {
    synchronized (lock) {
      if (started) {
        return;
      }
      started = true;

      createNewLeaderLatch();
      try {
        leaderLatch.get().start();
      }
      catch (Exception e) {
        throw Throwables.propagate(e);
      }
    }
  }

  private LeaderLatch createNewLeaderLatch()
  {
    final LeaderLatch newLeaderLatch = new LeaderLatch(
        curator, ZKPaths.makePath(config.getConnectorPath(), CONNECTOR_OWNER_NODE), self.getHost()
    );

    newLeaderLatch.addListener(
        new LeaderLatchListener()
        {
          @Override
          public void isLeader()
          {
            becomeLeader();
          }

          @Override
          public void notLeader()
          {
            stopBeingLeader();
          }
        },
        Execs.singleThreaded("CoordinatorLeader-%s")
    );

    return leaderLatch.getAndSet(newLeaderLatch);
  }

  @LifecycleStop
  public void stop()
  {
    synchronized (lock) {
      if (!started) {
        return;
      }

      stopBeingLeader();

      try {
        leaderLatch.get().close();
      }
      catch (IOException e) {
        log.warn(e, "Unable to close leaderLatch, ignoring");
      }

      exec.shutdownNow();

      started = false;
    }
  }

  private void becomeLeader()
  {
    synchronized (lock) {
      if (!started) {
        return;
      }

      log.info("Go-Go Gadgetmobile! Starting bridge in %s", config.getStartDelay());
      try {
        bridgeZkCoordinator.start();
        serverInventoryView.start();

        ScheduledExecutors.scheduleWithFixedDelay(
            exec,
            config.getStartDelay(),
            config.getPeriod(),
            new Callable<ScheduledExecutors.Signal>()
            {
              @Override
              public ScheduledExecutors.Signal call()
              {
                if (leader) {
                  Iterable<DruidServer> servers = FunctionalIterable
                      .create(serverInventoryView.getInventory())
                      .filter(
                          new Predicate<DruidServer>()
                          {
                            @Override
                            public boolean apply(
                                DruidServer input
                            )
                            {
                              return !input.getType().equalsIgnoreCase("realtime");
                            }
                          }
                      );

                  long totalMaxSize = 0;
                  for (DruidServer server : servers) {
                    totalMaxSize += server.getMaxSize();
                  }

                  if (totalMaxSize == 0) {
                    log.warn("No servers founds!");
                  } else {

                    DruidServerMetadata me = new DruidServerMetadata(
                        self.getHost(),
                        self.getHost(),
                        totalMaxSize,
                        NODE_TYPE,
                        config.getTier()
                    );

                    try {
                      final String path = ZKPaths.makePath(config.getAnnouncementsPath(), self.getHost());
                      log.info("Updating [%s] to have a maxSize of[%,d] bytes", self.getHost(), totalMaxSize);
                      announcer.update(path, jsonMapper.writeValueAsBytes(me));
                    }
                    catch (Exception e) {
                      throw Throwables.propagate(e);
                    }
                  }
                }
                if (leader) { // (We might no longer be coordinator)
                  return ScheduledExecutors.Signal.REPEAT;
                } else {
                  return ScheduledExecutors.Signal.STOP;
                }
              }
            }
        );

        leader = true;
      }
      catch (Exception e) {
        log.makeAlert(e, "Exception becoming leader")
           .emit();
        final LeaderLatch oldLatch = createNewLeaderLatch();
        Closeables.closeQuietly(oldLatch);
        try {
          leaderLatch.get().start();
        }
        catch (Exception e1) {
          // If an exception gets thrown out here, then the coordinator will zombie out 'cause it won't be looking for
          // the latch anymore.  I don't believe it's actually possible for an Exception to throw out here, but
          // Curator likes to have "throws Exception" on methods so it might happen...
          log.makeAlert(e1, "I am a zombie")
             .emit();
        }
      }
    }
  }

  private void stopBeingLeader()
  {
    synchronized (lock) {
      try {
        log.info("I'll get you next time, Gadget. Next time!");

        bridgeZkCoordinator.stop();
        serverInventoryView.stop();

        leader = false;
      }
      catch (Exception e) {
        log.makeAlert(e, "Unable to stopBeingLeader").emit();
      }
    }
  }
}
