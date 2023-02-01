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

package io.dapr.components.wrappers;

import com.google.protobuf.ByteString;
import dapr.proto.components.v1.PubSubGrpc;
import dapr.proto.components.v1.Pubsub;
import io.dapr.components.PubSubComponent;
import io.dapr.v1.ComponentProtos;
import io.grpc.stub.StreamObserver;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
@Log
public final class PubSubGrpcComponentWrapper extends PubSubGrpc.PubSubImplBase {

  @NonNull
  private final PubSubComponent pubSub;

  private final ConcurrentHashMap<String, Boolean> topicMap = new ConcurrentHashMap<>();

  /**
   * <pre>
   * Initializes the pubsub component with the given metadata.
   * </pre>
   */
  @Override
  public void init(Pubsub.PubSubInitRequest request,
                   StreamObserver<Pubsub.PubSubInitResponse> responseObserver) {
    log.info("Called pub pub init method");
    pubSub.init(request.getMetadata().getPropertiesMap());
    responseObserver.onNext(Pubsub.PubSubInitResponse.newBuilder().build());
    responseObserver.onCompleted();
  }

  /**
   * <pre>
   * Returns a list of implemented pubsub features.
   * </pre>
   */
  @Override
  public void features(io.dapr.v1.ComponentProtos.FeaturesRequest request,
                       StreamObserver<io.dapr.v1.ComponentProtos.FeaturesResponse> responseObserver) {
    ComponentProtos.FeaturesResponse response = ComponentProtos.FeaturesResponse.newBuilder().addFeatures(pubSub.getFeatures()).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  /**
   * <pre>
   * Publish publishes a new message for the given topic.
   * </pre>
   */
  @Override
  public void publish(Pubsub.PublishRequest request,
                      StreamObserver<Pubsub.PublishResponse> responseObserver) {
    log.info("Called pub sub publish ");
    final PubSubComponent.PubSubMessage message = fromGrpcType(request);
    pubSub.publish(message);
    responseObserver.onNext(Pubsub.PublishResponse.newBuilder().build());
    responseObserver.onCompleted();
  }

  /**
   * <pre>
   * Establishes a stream with the server (PubSub component), which sends
   * messages down to the client (daprd). The client streams acknowledgements
   * back to the server. The server will close the stream and return the status
   * on any error. In case of closed connection, the client should re-establish
   * the stream. The first message MUST contain a `topic` attribute on it that
   * should be used for the entire streaming pull.
   * </pre>
   */
  @Override
  public StreamObserver<Pubsub.PullMessagesRequest> pullMessages(
      StreamObserver<Pubsub.PullMessagesResponse> responseObserver) {
    return new StreamObserver<>() {
      @Override
      public void onNext(Pubsub.PullMessagesRequest value) {
        final Pubsub.Topic topic = value.getTopic();
        log.info("New subscription requested on topic " + topic);
        final BlockingQueue<PubSubComponent.PubSubMessage> subscription = pubSub.subscribe(topic.getName(), topic.getMetadataMap());

        Thread thread = new Thread(() -> {
          try {
            final PubSubComponent.PubSubMessage message = subscription.take();
            Pubsub.PullMessagesResponse pullMessagesResponse = Pubsub.PullMessagesResponse.newBuilder()
                .setContentType(message.getContentType())
                .setData(ByteString.copyFrom(message.getData()))
                .setTopicName(topic.getName()).setId(value.getAckMessageId()).build();
            responseObserver.onNext(pullMessagesResponse);
          } catch (InterruptedException e) {
            log.warning("Polling for messages on topic " + topic + " failed. Exception:" + e);
            responseObserver.onError(e);
          }
        });
        thread.start();

      }

      @Override
      public void onError(Throwable t) {
        log.info("Error on write stream：" + t.getMessage());

      }

      @Override
      public void onCompleted() {
        responseObserver.onCompleted();
      }
    };

  }

  /**
   * <pre>
   * Ping the pubsub. Used for liveness porpuses.
   * </pre>
   */
  @Override
  public void ping(ComponentProtos.PingRequest request,
                   StreamObserver<ComponentProtos.PingResponse> responseObserver) {

  }


  private static PubSubComponent.PubSubMessage fromGrpcType(@NonNull Pubsub.PublishRequest request) {
    return PubSubComponent.PubSubMessage.builder()
        .topic(request.getTopic())
        .pubsubName(request.getPubsubName())
        .data(request.getData().toByteArray())
        .metadata(request.getMetadataMap())
        .contentType(request.getContentType())
        .build();
  }


}
