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
package org.apache.beam.runners.fnexecution.control;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalNotification;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.net.HostAndPort;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.concurrent.ThreadSafe;
import org.apache.beam.model.fnexecution.v1.BeamFnApi.Target;
import org.apache.beam.model.pipeline.v1.RunnerApi.Environment;
import org.apache.beam.runners.core.construction.graph.ExecutableStage;
import org.apache.beam.runners.fnexecution.GrpcContextHeaderAccessorProvider;
import org.apache.beam.runners.fnexecution.GrpcFnServer;
import org.apache.beam.runners.fnexecution.ServerFactory;
import org.apache.beam.runners.fnexecution.artifact.ArtifactRetrievalService;
import org.apache.beam.runners.fnexecution.artifact.BeamFileSystemArtifactRetrievalService;
import org.apache.beam.runners.fnexecution.control.ProcessBundleDescriptors.ExecutableProcessBundleDescriptor;
import org.apache.beam.runners.fnexecution.control.SdkHarnessClient.BundleProcessor;
import org.apache.beam.runners.fnexecution.data.GrpcDataService;
import org.apache.beam.runners.fnexecution.environment.DockerEnvironmentFactory;
import org.apache.beam.runners.fnexecution.environment.EnvironmentFactory;
import org.apache.beam.runners.fnexecution.environment.RemoteEnvironment;
import org.apache.beam.runners.fnexecution.logging.GrpcLoggingService;
import org.apache.beam.runners.fnexecution.logging.Slf4jLogWriter;
import org.apache.beam.runners.fnexecution.provisioning.JobInfo;
import org.apache.beam.runners.fnexecution.provisioning.StaticGrpcProvisionService;
import org.apache.beam.runners.fnexecution.state.GrpcStateService;
import org.apache.beam.runners.fnexecution.state.StateRequestHandler;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.fn.IdGenerator;
import org.apache.beam.sdk.fn.IdGenerators;
import org.apache.beam.sdk.fn.data.FnDataReceiver;
import org.apache.beam.sdk.fn.stream.OutboundObserverFactory;
import org.apache.beam.sdk.util.WindowedValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link JobBundleFactory} that uses a {@link DockerEnvironmentFactory} for environment
 * management. Note that returned {@link StageBundleFactory stage bundle factories} are not
 * thread-safe. Instead, a new stage factory should be created for each client.
 */
@ThreadSafe
public class DockerJobBundleFactory implements JobBundleFactory {
  private static final Logger LOG = LoggerFactory.getLogger(DockerJobBundleFactory.class);

  // Port offset for MacOS since we don't have host networking and need to use published ports
  private static final int MAC_PORT_START = 8100;
  private static final int MAC_PORT_END = 8200;
  private static final AtomicInteger MAC_PORT = new AtomicInteger(MAC_PORT_START);

  /** Factory that creates {@link JobBundleFactory} for the given {@link JobInfo}. */
  public interface JobBundleFactoryFactory {
    DockerJobBundleFactory create(JobInfo jobInfo) throws Exception;
  }
  // TODO (BEAM-4819): a hacky way to override the factory for testing.
  // Should be replaced with mechanism that let's users configure their own factory
  public static final AtomicReference<JobBundleFactoryFactory> FACTORY =
      new AtomicReference(
          new JobBundleFactoryFactory() {
            @Override
            public DockerJobBundleFactory create(JobInfo jobInfo) throws Exception {
              return new DockerJobBundleFactory(jobInfo);
            }
          });

  // TODO: This host name seems to change with every other Docker release. Do we attempt to keep up
  // or attempt to document the supported Docker version(s)?
  private static final String DOCKER_FOR_MAC_HOST = "host.docker.internal";

  private final IdGenerator stageIdGenerator;
  private final GrpcFnServer<FnApiControlClientPoolService> controlServer;
  private final GrpcFnServer<GrpcLoggingService> loggingServer;
  private final GrpcFnServer<ArtifactRetrievalService> retrievalServer;
  private final GrpcFnServer<StaticGrpcProvisionService> provisioningServer;

  private final LoadingCache<Environment, WrappedSdkHarnessClient> environmentCache;

  public static DockerJobBundleFactory create(JobInfo jobInfo) throws Exception {
    return FACTORY.get().create(jobInfo);
  }

  protected DockerJobBundleFactory(JobInfo jobInfo) throws Exception {
    ServerFactory serverFactory = getServerFactory();
    IdGenerator stageIdGenerator = IdGenerators.incrementingLongs();
    ControlClientPool clientPool = MapControlClientPool.create();

    GrpcFnServer<FnApiControlClientPoolService> controlServer =
        GrpcFnServer.allocatePortAndCreateFor(
            FnApiControlClientPoolService.offeringClientsToPool(
                clientPool.getSink(), GrpcContextHeaderAccessorProvider.getHeaderAccessor()),
            serverFactory);
    GrpcFnServer<GrpcLoggingService> loggingServer =
        GrpcFnServer.allocatePortAndCreateFor(
            GrpcLoggingService.forWriter(Slf4jLogWriter.getDefault()), serverFactory);
    GrpcFnServer<ArtifactRetrievalService> retrievalServer =
        GrpcFnServer.allocatePortAndCreateFor(
            BeamFileSystemArtifactRetrievalService.create(), serverFactory);
    GrpcFnServer<StaticGrpcProvisionService> provisioningServer =
        GrpcFnServer.allocatePortAndCreateFor(
            StaticGrpcProvisionService.create(jobInfo.toProvisionInfo()), serverFactory);
    EnvironmentFactory environmentFactory =
        getEnvironmentFactory(
            controlServer,
            loggingServer,
            retrievalServer,
            provisioningServer,
            clientPool.getSource(),
            IdGenerators.incrementingLongs());
    this.stageIdGenerator = stageIdGenerator;
    this.controlServer = controlServer;
    this.loggingServer = loggingServer;
    this.retrievalServer = retrievalServer;
    this.provisioningServer = provisioningServer;
    this.environmentCache = createEnvironmentCache(environmentFactory, serverFactory);
  }

  @VisibleForTesting
  DockerJobBundleFactory(
      EnvironmentFactory environmentFactory,
      ServerFactory serverFactory,
      IdGenerator stageIdGenerator,
      GrpcFnServer<FnApiControlClientPoolService> controlServer,
      GrpcFnServer<GrpcLoggingService> loggingServer,
      GrpcFnServer<ArtifactRetrievalService> retrievalServer,
      GrpcFnServer<StaticGrpcProvisionService> provisioningServer) {
    this.stageIdGenerator = stageIdGenerator;
    this.controlServer = controlServer;
    this.loggingServer = loggingServer;
    this.retrievalServer = retrievalServer;
    this.provisioningServer = provisioningServer;
    this.environmentCache = createEnvironmentCache(environmentFactory, serverFactory);
  }

  private LoadingCache<Environment, WrappedSdkHarnessClient> createEnvironmentCache(
      EnvironmentFactory environmentFactory, ServerFactory serverFactory) {
    return CacheBuilder.newBuilder()
        .removalListener(
            ((RemovalNotification<Environment, WrappedSdkHarnessClient> notification) -> {
              LOG.debug("Cleaning up for environment {}", notification.getKey().getUrl());
              try {
                notification.getValue().close();
              } catch (Exception e) {
                LOG.warn(
                    String.format("Error cleaning up environment %s", notification.getKey()), e);
              }
            }))
        .build(
            new CacheLoader<Environment, WrappedSdkHarnessClient>() {
              @Override
              public WrappedSdkHarnessClient load(Environment environment) throws Exception {
                RemoteEnvironment remoteEnvironment =
                    environmentFactory.createEnvironment(environment);
                return WrappedSdkHarnessClient.wrapping(remoteEnvironment, serverFactory);
              }
            });
  }

  @Override
  public StageBundleFactory forStage(ExecutableStage executableStage) {
    WrappedSdkHarnessClient wrappedClient =
        environmentCache.getUnchecked(executableStage.getEnvironment());
    ExecutableProcessBundleDescriptor processBundleDescriptor;
    try {
      processBundleDescriptor =
          ProcessBundleDescriptors.fromExecutableStage(
              stageIdGenerator.getId(),
              executableStage,
              wrappedClient.getDataServer().getApiServiceDescriptor(),
              wrappedClient.getStateServer().getApiServiceDescriptor());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return SimpleStageBundleFactory.create(wrappedClient, processBundleDescriptor);
  }

  @Override
  public void close() throws Exception {
    // Clear the cache. This closes all active environments.
    environmentCache.invalidateAll();
    environmentCache.cleanUp();

    // Tear down common servers.
    controlServer.close();
    loggingServer.close();
    retrievalServer.close();
    provisioningServer.close();
  }

  protected ServerFactory getServerFactory() {
    switch (getPlatform()) {
      case LINUX:
        return ServerFactory.createDefault();
      case MAC:
        // NOTE: Deployment on Macs is intended for local development. As of 18.03, Docker-for-Mac
        // does not implement host networking (--networking=host is effectively a no-op). Instead,
        // we use a special DNS entry that points to the host:
        // https://docs.docker.com/docker-for-mac/networking/#use-cases-and-workarounds
        // The special hostname has historically changed between versions, so this is subject to
        // breakages and will likely only support the latest version at any time.

        // We need to use a fixed port range due to non-existing host networking in Docker-for-Mac.
        // The port range needs to be published when bringing up the Docker container, see
        // DockerEnvironmentFactory.

        return ServerFactory.createWithUrlFactoryAndPortSupplier(
            (host, port) -> HostAndPort.fromParts(DOCKER_FOR_MAC_HOST, port).toString(),
            // We only use the published Docker ports 8100-8200 in a round-robin fashion
            () -> MAC_PORT.getAndUpdate(val -> val == MAC_PORT_END ? MAC_PORT_START : val + 1));
      default:
        LOG.warn("Unknown Docker platform. Falling back to default server factory");
        return ServerFactory.createDefault();
    }
  }

  private static Platform getPlatform() {
    String osName = System.getProperty("os.name").toLowerCase();
    // TODO: Make this more robust?
    // The DOCKER_MAC_CONTAINER environment variable is necessary to detect whether we run on
    // a container on MacOs. MacOs internally uses a Linux VM which makes it indistinguishable from Linux.
    // We still need to apply port mapping due to missing host networking.
    if (osName.startsWith("mac") || "1".equals(System.getenv("DOCKER_MAC_CONTAINER"))) {
      return Platform.MAC;
    } else if (osName.startsWith("linux")) {
      return Platform.LINUX;
    }
    return Platform.OTHER;
  }

  private static class SimpleStageBundleFactory implements StageBundleFactory {

    private final BundleProcessor processor;
    private final ExecutableProcessBundleDescriptor processBundleDescriptor;

    // Store the wrapped client in order to keep a live reference into the cache.
    private WrappedSdkHarnessClient wrappedClient;

    static SimpleStageBundleFactory create(
        WrappedSdkHarnessClient wrappedClient,
        ExecutableProcessBundleDescriptor processBundleDescriptor) {
      @SuppressWarnings("unchecked")
      BundleProcessor processor =
          wrappedClient
              .getClient()
              .getProcessor(
                  processBundleDescriptor.getProcessBundleDescriptor(),
                  processBundleDescriptor.getRemoteInputDestinations(),
                  wrappedClient.getStateServer().getService());
      return new SimpleStageBundleFactory(processBundleDescriptor, processor, wrappedClient);
    }

    SimpleStageBundleFactory(
        ExecutableProcessBundleDescriptor processBundleDescriptor,
        BundleProcessor processor,
        WrappedSdkHarnessClient wrappedClient) {
      this.processBundleDescriptor = processBundleDescriptor;
      this.processor = processor;
      this.wrappedClient = wrappedClient;
    }

    @Override
    public RemoteBundle getBundle(
        OutputReceiverFactory outputReceiverFactory,
        StateRequestHandler stateRequestHandler,
        BundleProgressHandler progressHandler)
        throws Exception {
      // TODO: Consider having BundleProcessor#newBundle take in an OutputReceiverFactory rather
      // than constructing the receiver map here. Every bundle factory will need this.
      ImmutableMap.Builder<Target, RemoteOutputReceiver<?>> outputReceivers =
          ImmutableMap.builder();
      for (Map.Entry<Target, Coder<WindowedValue<?>>> targetCoder :
          processBundleDescriptor.getOutputTargetCoders().entrySet()) {
        Target target = targetCoder.getKey();
        Coder<WindowedValue<?>> coder = targetCoder.getValue();
        String bundleOutputPCollection =
            Iterables.getOnlyElement(
                processBundleDescriptor
                    .getProcessBundleDescriptor()
                    .getTransformsOrThrow(target.getPrimitiveTransformReference())
                    .getInputsMap()
                    .values());
        FnDataReceiver<WindowedValue<?>> outputReceiver =
            outputReceiverFactory.create(bundleOutputPCollection);
        outputReceivers.put(target, RemoteOutputReceiver.of(coder, outputReceiver));
      }
      return processor.newBundle(outputReceivers.build(), stateRequestHandler, progressHandler);
    }

    @Override
    public void close() throws Exception {
      // Clear reference to encourage cache eviction. Values are weakly referenced.
      wrappedClient = null;
    }
  }

  /**
   * Holder for an {@link SdkHarnessClient} along with its associated state and data servers. As of
   * now, there is a 1:1 relationship between data services and harness clients. The servers are
   * packaged here to tie server lifetimes to harness client lifetimes.
   */
  private static class WrappedSdkHarnessClient implements AutoCloseable {
    private final RemoteEnvironment environment;
    private final ExecutorService executor;
    // TODO: How should data server lifetime be scoped? It is necessary here for now because
    // SdkHarnessClient requires one at construction.
    private final GrpcFnServer<GrpcDataService> dataServer;
    private final GrpcFnServer<GrpcStateService> stateServer;
    private final SdkHarnessClient client;

    static WrappedSdkHarnessClient wrapping(
        RemoteEnvironment environment, ServerFactory serverFactory) throws Exception {
      ExecutorService executor = Executors.newCachedThreadPool();
      GrpcFnServer<GrpcDataService> dataServer =
          GrpcFnServer.allocatePortAndCreateFor(
              GrpcDataService.create(executor, OutboundObserverFactory.serverDirect()),
              serverFactory);
      GrpcFnServer<GrpcStateService> stateServer =
          GrpcFnServer.allocatePortAndCreateFor(GrpcStateService.create(), serverFactory);
      SdkHarnessClient client =
          SdkHarnessClient.usingFnApiClient(
              environment.getInstructionRequestHandler(), dataServer.getService());
      return new WrappedSdkHarnessClient(environment, executor, dataServer, stateServer, client);
    }

    private WrappedSdkHarnessClient(
        RemoteEnvironment environment,
        ExecutorService executor,
        GrpcFnServer<GrpcDataService> dataServer,
        GrpcFnServer<GrpcStateService> stateServer,
        SdkHarnessClient client) {
      this.executor = executor;
      this.environment = environment;
      this.dataServer = dataServer;
      this.stateServer = stateServer;
      this.client = client;
    }

    SdkHarnessClient getClient() {
      return client;
    }

    GrpcFnServer<GrpcStateService> getStateServer() {
      return stateServer;
    }

    GrpcFnServer<GrpcDataService> getDataServer() {
      return dataServer;
    }

    @Override
    public void close() throws Exception {
      try (AutoCloseable stateServerCloser = stateServer;
          AutoCloseable dataServerCloser = dataServer;
          AutoCloseable envCloser = environment;
          AutoCloseable executorCloser = executor::shutdown) {
        // Wrap resources in try-with-resources to ensure all are cleaned up.
      }
      // TODO: Wait for executor shutdown?
    }
  }

  private enum Platform {
    MAC,
    LINUX,
    OTHER,
  }

  /** Create {@link EnvironmentFactory} for the given services. */
  protected EnvironmentFactory getEnvironmentFactory(
      GrpcFnServer<FnApiControlClientPoolService> controlServiceServer,
      GrpcFnServer<GrpcLoggingService> loggingServiceServer,
      GrpcFnServer<ArtifactRetrievalService> retrievalServiceServer,
      GrpcFnServer<StaticGrpcProvisionService> provisioningServiceServer,
      ControlClientPool.Source clientSource,
      IdGenerator idGenerator) {
    return DockerEnvironmentFactory.forServices(
        controlServiceServer,
        loggingServiceServer,
        retrievalServiceServer,
        provisioningServiceServer,
        clientSource,
        idGenerator);
  }
}