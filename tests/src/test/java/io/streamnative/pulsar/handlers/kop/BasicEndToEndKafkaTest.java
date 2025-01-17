/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.streamnative.pulsar.handlers.kop;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import io.streamnative.pulsar.handlers.kop.utils.KopTopic;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.Cleanup;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.TopicPartition;
import org.apache.pulsar.broker.service.persistent.PersistentTopic;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.policies.data.ConsumerStats;
import org.testng.annotations.Test;

/**
 * Basic end-to-end test with `entryFormat=kafka`.
 */
@Slf4j
public class BasicEndToEndKafkaTest extends BasicEndToEndTestBase {

    public BasicEndToEndKafkaTest() {
        super("kafka");
    }

    @Test(timeOut = 20000)
    public void testNullValueMessages() throws Exception {
        final String topic = "test-produce-null-value";

        @Cleanup
        final KafkaProducer<String, String> kafkaProducer = newKafkaProducer();
        sendSingleMessages(kafkaProducer, topic, Arrays.asList(null, ""));
        sendBatchedMessages(kafkaProducer, topic, Arrays.asList("test", "", null));

        final List<String> expectedMessages = Arrays.asList(null, "", "test", "", null);

        @Cleanup
        final KafkaConsumer<String, String> kafkaConsumer = newKafkaConsumer(topic);
        List<String> kafkaReceives = receiveMessages(kafkaConsumer, expectedMessages.size());
        assertEquals(kafkaReceives, expectedMessages);
    }

    @Test(timeOut = 20000)
    public void testDeleteClosedTopics() throws Exception {
        final String topic = "test-delete-closed-topics";
        final List<String> expectedMessages = Collections.singletonList("msg");

        admin.topics().createPartitionedTopic(topic, 1);
        final KafkaProducer<String, String> kafkaProducer = newKafkaProducer();
        sendSingleMessages(kafkaProducer, topic, expectedMessages);

        try {
            admin.topics().deletePartitionedTopic(topic);
            fail();
        } catch (PulsarAdminException e) {
            log.info("Failed to delete partitioned topic \"{}\": {}", topic, e.getMessage());
            assertTrue(e.getMessage().contains("Topic has active producers/subscriptions")
                    || e.getMessage().contains("Partitioned topic does not exist"));
        }

        final KafkaConsumer<String, String> kafkaConsumer1 = newKafkaConsumer(topic, "sub-1");
        assertEquals(receiveMessages(kafkaConsumer1, expectedMessages.size()), expectedMessages);
        try {
            admin.topics().deletePartitionedTopic(topic);
            fail();
        } catch (PulsarAdminException e) {
            log.info("Failed to delete partitioned topic \"{}\": {}", topic, e.getMessage());
            assertTrue(e.getMessage().contains("Topic has active producers/subscriptions")
                    || e.getMessage().contains("Partitioned topic does not exist"));
        }

        final KafkaConsumer<String, String> kafkaConsumer2 = newKafkaConsumer(topic, "sub-2");
        assertEquals(receiveMessages(kafkaConsumer2, expectedMessages.size()), expectedMessages);

        kafkaProducer.close();
        kafkaConsumer1.close();
        try {
            admin.topics().deletePartitionedTopic(topic);
            fail();
        } catch (PulsarAdminException e) {
            log.info("Failed to delete partitioned topic \"{}\": {}", topic, e.getMessage());
            assertTrue(e.getMessage().contains("Topic has active producers/subscriptions")
                    || e.getMessage().contains("Partitioned topic does not exist"));
        }

        kafkaConsumer2.close();
        Thread.sleep(500); // Wait for consumers closed
        admin.topics().deletePartitionedTopic(topic);
    }

    @Test(timeOut = 20000)
    public void testKafkaConsumerMetrics() throws Exception {
        final String topic = "test-kafka-consumer-metrics";
        final String group = "group-test-kafka-consumer-metrics";
        final List<String> expectedMessages = Arrays.asList("A", "B", "C");

        @Cleanup
        final KafkaProducer<String, String> kafkaProducer = newKafkaProducer();
        sendSingleMessages(kafkaProducer, topic, expectedMessages);

        final Properties consumerProps = newKafkaConsumerProperties();
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        @Cleanup
        final KafkaConsumer<String, String> kafkaConsumer = new KafkaConsumer<>(consumerProps);
        kafkaConsumer.subscribe(Collections.singleton(topic));
        List<String> kafkaReceives = receiveMessages(kafkaConsumer, expectedMessages.size());
        assertEquals(kafkaReceives, expectedMessages);

        // Check stats
        final TopicName topicName = TopicName.get(KopTopic.toString(new TopicPartition(topic, 0)));
        final PersistentTopic persistentTopic = (PersistentTopic) pulsar.getBrokerService().getMultiLayerTopicsMap()
                .get(topicName.getNamespace())
                .get(pulsar.getNamespaceService().getBundle(topicName).toString())
                .get(topicName.toString());
        final ConsumerStats stats =
                persistentTopic.getSubscriptions().get(group).getDispatcher().getConsumers().get(0).getStats();
        log.info("Consumer stats: [msgOutCounter={}] [bytesOutCounter={}]",
                stats.msgOutCounter, stats.bytesOutCounter);
        assertEquals(stats.msgOutCounter, expectedMessages.size());
        assertTrue(stats.bytesOutCounter > 0);
    }

    @Test(timeOut = 30000)
    public void testPollEmptyTopic() throws Exception {
        int partitionNumber = 50;
        String kafkaTopic = "kopPollEmptyTopic" + partitionNumber;
        String pulsarTopicName = "persistent://public/default/" + kafkaTopic;

        admin.topics().createPartitionedTopic(pulsarTopicName, partitionNumber);
        int totalMsg = 500;

        String msgStrPrefix = "Message_kop_KafkaProduceAndConsume_" + partitionNumber + "_";
        @Cleanup
        KProducer kProducer = new KProducer(kafkaTopic, false, getKafkaBrokerPort(), true);
        kafkaPublishMessage(kProducer, totalMsg, msgStrPrefix);

        @Cleanup
        KConsumer kConsumer1 = new KConsumer(kafkaTopic, getKafkaBrokerPort(), "consumer-group-1");
        @Cleanup
        KConsumer kConsumer2 = new KConsumer(kafkaTopic, getKafkaBrokerPort(), "consumer-group-2");
        @Cleanup
        KConsumer kConsumer3 = new KConsumer(kafkaTopic, getKafkaBrokerPort(), "consumer-group-3");
        @Cleanup
        KConsumer kConsumer4 = new KConsumer(kafkaTopic, getKafkaBrokerPort(), "consumer-group-4");
        @Cleanup
        KConsumer kConsumer5 = new KConsumer(kafkaTopic, getKafkaBrokerPort(), "consumer-group-5");

        List<TopicPartition> topicPartitions = IntStream.range(0, partitionNumber)
            .mapToObj(i -> new TopicPartition(kafkaTopic, i)).collect(Collectors.toList());

        kafkaConsumeCommitMessage(kConsumer1, totalMsg, msgStrPrefix, topicPartitions);
        kafkaConsumeCommitMessage(kConsumer2, totalMsg, msgStrPrefix, topicPartitions);
        kafkaConsumeCommitMessage(kConsumer3, totalMsg, msgStrPrefix, topicPartitions);
        kafkaConsumeCommitMessage(kConsumer4, totalMsg, msgStrPrefix, topicPartitions);
        kafkaConsumeCommitMessage(kConsumer5, totalMsg, msgStrPrefix, topicPartitions);

        ConsumerRecords<Integer, String> records = kConsumer1.getConsumer().poll(Duration.ofMillis(200));
        assertTrue(records.isEmpty());
        records = kConsumer2.getConsumer().poll(Duration.ofMillis(200));
        assertTrue(records.isEmpty());
        records = kConsumer3.getConsumer().poll(Duration.ofMillis(200));
        assertTrue(records.isEmpty());
        records = kConsumer4.getConsumer().poll(Duration.ofMillis(200));
        assertTrue(records.isEmpty());
        records = kConsumer5.getConsumer().poll(Duration.ofMillis(200));
        assertTrue(records.isEmpty());

        kafkaPublishMessage(kProducer, totalMsg, msgStrPrefix);

        kafkaConsumeCommitMessage(kConsumer1, totalMsg, msgStrPrefix, topicPartitions);
        kafkaConsumeCommitMessage(kConsumer2, totalMsg, msgStrPrefix, topicPartitions);
        kafkaConsumeCommitMessage(kConsumer3, totalMsg, msgStrPrefix, topicPartitions);
        kafkaConsumeCommitMessage(kConsumer4, totalMsg, msgStrPrefix, topicPartitions);
        kafkaConsumeCommitMessage(kConsumer5, totalMsg, msgStrPrefix, topicPartitions);

    }
}
