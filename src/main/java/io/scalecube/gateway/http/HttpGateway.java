package io.scalecube.gateway.http;

import io.netty.channel.EventLoopGroup;
import io.scalecube.services.ServiceCall;
import io.scalecube.services.gateway.Gateway;
import io.scalecube.services.gateway.GatewayConfig;
import io.scalecube.services.metrics.Metrics;
import java.net.InetSocketAddress;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.http.server.HttpServer;
import reactor.ipc.netty.tcp.BlockingNettyContext;

public class HttpGateway implements Gateway {

  private static final Logger LOGGER = LoggerFactory.getLogger(HttpGateway.class);

  private BlockingNettyContext server;

  @Override
  public Mono<InetSocketAddress> start(
    GatewayConfig config,
    Executor selectorThreadPool,
    Executor workerThreadPool,
    ServiceCall.Call call,
      Metrics metrics) {

    return Mono.defer(
      () -> {
        LOGGER.info("Starting gateway with {}", config);

        InetSocketAddress listenAddress = new InetSocketAddress(config.port());
        GatewayHttpAcceptor acceptor = new GatewayHttpAcceptor(call.create());

        server =
          HttpServer.builder()
            .options(
              opts -> {
                opts.listenAddress(listenAddress);
                if (selectorThreadPool != null && workerThreadPool != null) {
                  opts.loopResources(
                    new HttpLoopResources(
                      (EventLoopGroup) selectorThreadPool,
                      (EventLoopGroup) workerThreadPool));
                }
              })
            .build()
            .start(acceptor);
        server.installShutdownHook();
        InetSocketAddress address = server.getContext().address();

        LOGGER.info("Gateway has been started successfully on {}", address);

        return Mono.just(address);
      });
  }

  @Override
  public Mono<Void> stop() {
    return Mono.fromRunnable(
      () -> {
        if (server != null) {
          server.shutdown();
        }
      });
  }
}
