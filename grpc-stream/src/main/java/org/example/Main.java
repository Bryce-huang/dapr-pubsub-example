package org.example;

import dapr.proto.components.v1.PubSubGrpc;
import dapr.proto.components.v1.Pubsub;
import io.grpc.ManagedChannel;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollDomainSocketChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueDomainSocketChannel;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.channel.unix.DomainSocketChannel;
import lombok.extern.java.Log;

@Log
public class Main {
  public static void main(String[] args) throws InterruptedException {

    final EventLoopGroup eventLoopGroup;
    final Class<? extends DomainSocketChannel> serverChannelClass;
    if (KQueue.isAvailable()) {
      log.info("Using KQueue");
      eventLoopGroup = new KQueueEventLoopGroup();
      serverChannelClass = KQueueDomainSocketChannel.class;
    } else {
      log.info("Using Epoll");
      eventLoopGroup = new EpollEventLoopGroup();
      serverChannelClass = EpollDomainSocketChannel.class;
    }


    ManagedChannel channel = NettyChannelBuilder.forAddress(new DomainSocketAddress("/tmp/dapr-components-sockets/queue.sock"))
        .eventLoopGroup(eventLoopGroup)
        .channelType(serverChannelClass)
        .usePlaintext()
        .build();


    StreamObserver<Pubsub.PullMessagesRequest> req = PubSubGrpc.newStub(channel).pullMessages(new StreamObserver<>() {
      @Override
      public void onNext(Pubsub.PullMessagesResponse value) {
        log.info("recv:" + value);

      }

      @Override
      public void onError(Throwable t) {
        t.printStackTrace();
      }

      @Override
      public void onCompleted() {

      }


    });

    req.onNext(Pubsub.PullMessagesRequest.newBuilder().setTopic(Pubsub.Topic.newBuilder().setName("123456").build()).build());


  }


}