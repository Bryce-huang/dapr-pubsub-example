/*
 * Copyright 2022 The Dapr Authors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.dapr.components.cli;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionService;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerDomainSocketChannel;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.kqueue.KQueueServerDomainSocketChannel;
import io.netty.channel.unix.DomainSocketAddress;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Wraps a GRPC service exposing a Dapr pluggable component into a fully functional executable.
 *
 * <p>Takes care of:
 * 1. CLI argument parsing,
 * 2. setting up Unix Domain Socket files,
 * 3. environment variable handling,
 * 4. setting up the required server machinery to expose the service and handle requests for it.
 *
 * <p>If you end up needing to extend functionality of this class it might be simpler re-implementing it.
 */
@Log
@RequiredArgsConstructor
public class PluggableComponentServer {
    /**
     * GRPC service for the Dapr component you want to have exposed.
     */
    @NonNull
    final BindableService exposedService;
    /**
     * Nane of the current CLI program. This will be used for constructing our --help messages.
     */
    @NonNull
    private String programName;
    // Initialized by run, which is the only public method in this class.
    private Server server;

    private static Server buildUnixSocketServer(
        @NonNull final String unixSocketPath,
        @NonNull final BindableService exposedService) throws IOException {
        log.info("Configuring server to listen to unix socket domain on file " + unixSocketPath);
        // If file exists, remove it.
        final File unixSocketFile = new File(unixSocketPath);
        if (unixSocketFile.exists()) {
            log.warning("Unix Socket Descriptor in [" + unixSocketPath + "] already exists. "
                + "Removing it to recreate it.");
            Files.deleteIfExists(unixSocketFile.toPath());
        }
        // Regardless, delete this file on exist. Just good hygiene ;)
        unixSocketFile.deleteOnExit();


        final DomainSocketAddress unixSocket = new DomainSocketAddress(unixSocketPath);
        final EventLoopGroup eventLoopGroup;
        final Class<? extends ServerChannel> serverChannelClass;
        if (KQueue.isAvailable()) {
            log.info("Using KQueue");
            eventLoopGroup = new KQueueEventLoopGroup();
            serverChannelClass = KQueueServerDomainSocketChannel.class;
        } else {
            log.info("Using Epoll");
            eventLoopGroup = new EpollEventLoopGroup();
            serverChannelClass = EpollServerDomainSocketChannel.class;
        }
        return NettyServerBuilder.forAddress(unixSocket)
            .channelType(serverChannelClass)
            .workerEventLoopGroup(eventLoopGroup)
            .bossEventLoopGroup(eventLoopGroup)
            .addService(exposedService)
            .addService(ProtoReflectionService.newInstance())
            .build();
    }

    private static Server setupTcpServer(int port, @NonNull final BindableService exposedService) {
        log.info("Configuring server to listen on TCP port " + port);
        return ServerBuilder.forPort(port)
            .addService(exposedService)
            .build();
    }

    /**
     * Setups the machinery and wrappers to expose your service as a server.
     *
     * <p>Takes care of setting up a server and parsing command line to serve your
     * service.</p>
     *
     * <p>Ideally a server can only expose/serve a single service. If you don't
     * know what you are doing, do no try to expose more than once service / component
     * with the same server. While possible, this is not the model that Dapr itself
     * expects from components and it won't know how to handle multiple components
     * being served from the same UNIX socket.</p>
     *
     * @param args Command line arguments that needs to be parsed.
     * @throws IOException          Thrown if an error happened while running the service.
     * @throws InterruptedException Thrown if an error happened while running the service.
     */
    public void main(@NonNull final String[] args) throws IOException, InterruptedException {
        // Command line parsing
        final CommandLineOptions options = new CommandLineOptions();

        // Either Unix Socket Domains or TCP, but not both
        final Optional<String> maybeUnixSocketPath = options.getUnixSocketPathFromArgsOrEnv();


        // Start server
        log.info("Starting server for " + programName + "...");
        server = buildUnixSocketServer(maybeUnixSocketPath.get(), exposedService);

        start();
        updateSocketPermissionInBackground();
        blockUntilShutdown();
    }

    private static void updateSocketPermissionInBackground() {
        new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    final File unixSocketFile = new File("/tmp/dapr-components-sockets/queue.sock");
                    if (unixSocketFile.exists()) {
                        boolean success = true;
                        if (!unixSocketFile.setReadable(true, false)) {
                            log.warning("Failed to set socket readable");
                            success = false;
                        }
                        if (!unixSocketFile.setWritable(true, false)) {
                            log.warning("Failed to set socket writable");
                            success = false;
                        }
                        if (success) {
                            log.info("Successfully set socket permissions on " + "/tmp/dapr-components-sockets/queue.sock");
                            return;
                        }
                    }
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }).start();
    }

    private void start() throws IOException {
        server.start();
        log.info("Server started.");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Use stderr here since the logger may have been reset by its JVM shutdown hook.
            System.err.println("*** shutting down gRPC server since JVM is shutting down");
            try {
                PluggableComponentServer.this.stop();
            } catch (InterruptedException e) {
                e.printStackTrace(System.err);
            }
            System.err.println("*** server shut down");
        }));
    }

    private void stop() throws InterruptedException {
        server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
    }

    /**
     * Await termination on the main thread since the grpc library uses daemon threads.
     */
    private void blockUntilShutdown() throws InterruptedException {
        server.awaitTermination();
    }

}
