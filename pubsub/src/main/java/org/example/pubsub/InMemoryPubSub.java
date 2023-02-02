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

package org.example.pubsub;

import com.google.common.collect.Maps;
import io.dapr.components.PubSubComponent;
import lombok.NonNull;
import lombok.Value;
import lombok.extern.java.Log;

import java.util.Map;
import java.util.concurrent.*;

@Log
public class InMemoryPubSub implements PubSubComponent {

  ConcurrentMap<String, SubscriberList> perTopicSubscribers = new ConcurrentHashMap<>();

  @Override
  public void init(@NonNull Map<String, String> properties) {
    log.info("Initializing InMemoryPubSub Store.");
  }

  @Override
  public void ping() {
    log.info("InMemoryPubSub - ping requested");
  }

  @Override
  public String getFeatures() {
    return "";
  }

  @Override
  public void publish(@NonNull final PubSubMessage message) {
    final String topic = message.getTopic();
    getSubscribersForTopic(topic).subscribers.parallelStream().forEach(s -> {
      try {
        // the big assumption here is that we won't block while adding messages to those queues
        // as those are unbounded LinkedBlockingList: we are not waiting for space to become
        // available because as long as there's memory, there's space ;)
        s.put(message);
      } catch (InterruptedException e) {
        log.warning("Error publishing message to topic " + topic);
        throw new RuntimeException(e);
      }
    });
  }

  @Override
  public BlockingQueue<PubSubMessage> subscribe(@NonNull String topic, @NonNull Map<String, String> metadata) {
    // This is an unbounded LinkedBlockingList.
    final BlockingQueue<PubSubMessage> subscription = new LinkedBlockingQueue<>();
    getSubscribersForTopic(topic).subscribers.add(subscription);
// Simulate message subscription
    new Thread(() -> {
      int id = 0;
      for (; ; ) {
        try {
          Thread.sleep(1000);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
        subscription.add(PubSubMessage.builder().pubsubName("queue").contentType("application/json").data(String.format("{\"xxx\":%d}", id++).getBytes()).topic(topic).metadata(Maps.newHashMap()).build());
      }
    }).start();


    return subscription;
  }

  private SubscriberList getSubscribersForTopic(@NonNull String topic) {
    return perTopicSubscribers.computeIfAbsent(topic, s -> new SubscriberList());
  }

  // Missing a typedef-like  declaration in java ;)
  @Value
  public static class SubscriberList {
    @NonNull ConcurrentLinkedQueue<BlockingQueue<PubSubMessage>> subscribers = new ConcurrentLinkedQueue<>();
  }
}
