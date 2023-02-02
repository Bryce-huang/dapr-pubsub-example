package org.example;

import io.dapr.client.DaprClient;
import io.dapr.client.DaprClientBuilder;
import io.dapr.client.domain.Metadata;
import lombok.extern.java.Log;

import java.util.Collections;
import java.util.Random;
import java.util.concurrent.TimeUnit;
@Log
public class Main {
  public static void main(String[] args) throws InterruptedException {

    while (true) {
      TimeUnit.MICROSECONDS.sleep(2000);
      Random random=new Random();
      int id =random.nextInt(1000-1)+1;
      DaprClient client = new DaprClientBuilder().build();

      client.publishEvent("rmb","orders",id, Collections.singletonMap(Metadata.TTL_IN_SECONDS,"1000")).block();
      log.info("published data:"+id);
    }

  }
}