/*
 * Copyright 2015 Michal Harish, michal.harish@gmail.com
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.amient.kafka.metrics;

import kafka.consumer.ConsumerConfig;
import kafka.consumer.ConsumerIterator;
import kafka.javaapi.consumer.ConsumerConnector;
import kafka.consumer.KafkaStream;
import kafka.message.MessageAndMetadata;
import kafka.serializer.StringDecoder;
import kafka.utils.VerifiableProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ConsumerMetrics {

    static private final Logger log = LoggerFactory.getLogger(ConsumerMetrics.class);

    private final ExecutorService executor;

    public ConsumerMetrics(Properties props) {
        String topic = props.getProperty("consumer.topic", "metrics");
        Integer numThreads = Integer.parseInt(props.getProperty("consumer.numThreads", "1"));
        executor = Executors.newFixedThreadPool(numThreads);

        Properties consumerProps = new Properties();
        for (Enumeration<Object> e = props.keys(); e.hasMoreElements(); ) {
            String propKey = (String) e.nextElement();
            String propVal = props.get(propKey).toString();
            if (propKey.startsWith("consumer.")) {
                propKey = propKey.substring(9);
                consumerProps.put(propKey, propVal);
                log.info(propKey + "=" + propVal);
            }
        }

        if (consumerProps.size() == 0) {
            log.info("ConsumerMetrics disabled: " + executor.isTerminated());
            return;
        }

        VerifiableProperties config = new VerifiableProperties(consumerProps);
        ConsumerConnector consumer = kafka.consumer.Consumer.createJavaConsumerConnector(
                new ConsumerConfig(config.props()));

        Map<String, Integer> topicCountMap = new HashMap<String, Integer>();
        topicCountMap.put(topic, new Integer(numThreads));
        Map<String, List<KafkaStream<String, List<MeasurementV1>>>> consumerMap
                = consumer.createMessageStreams(topicCountMap, new StringDecoder(config), new MeasurementDecoder(config));

        List<KafkaStream<String, List<MeasurementV1>>> streams = consumerMap.get(topic);

        for (final KafkaStream<String, List<MeasurementV1>> stream : streams) {
            executor.submit(new Task(new InfluxDbPublisher(props), stream));
        }
        executor.shutdown();

    }

    public boolean isTerminated() {
        return executor.isTerminated();
    }

    public static class Task implements Runnable {
        final private KafkaStream<String, List<MeasurementV1>> stream;
        final private MeasurementFormatter formatter;
        final private MeasurementPublisher publisher;

        public Task(MeasurementPublisher publisher, KafkaStream<String, List<MeasurementV1>> stream) {
            this.stream = stream;
            this.formatter = new MeasurementFormatter();
            this.publisher = publisher;
        }

        public void run() {
            ConsumerIterator<String, List<MeasurementV1>> it = stream.iterator();
            try {
                while (it.hasNext()) {
                    try {
                        MessageAndMetadata<String, List<MeasurementV1>> m = it.next();
                        if (m.message() != null) {
                            for (MeasurementV1 measurement : m.message()) {
                                try {
                                    publisher.publish(measurement);
//                                    if (measurement.getName().equals("MessagesInPerSec")) {
//                                        formatter.writeTo(measurement, System.out);
//                                    }
                                } catch (RuntimeException e) {

                                    log.error("Unable to publish measurement " + formatter.toString(measurement)
                                            + "tag count=" + measurement.getFields().size()
                                            + ", field count=" + measurement.getFields().size()
                                            , e);

                                }
                            }
                        }
                    } catch (Throwable e) {
                        e.printStackTrace();
                        return;
                    }
                }
            } finally {
                System.out.println("Finished metrics consumer task");
                publisher.close();
            }
        }
    }

}
