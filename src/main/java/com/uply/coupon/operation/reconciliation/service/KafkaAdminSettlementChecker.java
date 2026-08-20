package com.uply.coupon.operation.reconciliation.service;

import com.uply.coupon.operation.reconciliation.domain.KafkaSettlement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** V3가 DB 최종 정합성을 판정하기 전에 Kafka 적체와 DLT를 확인한다. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "coupon.save.strategy", havingValue = "kafka")
public class KafkaAdminSettlementChecker implements KafkaSettlementChecker {

    private static final long TIMEOUT_SECONDS = 5L;
    private static final String ISSUE_TOPIC = "coupon-issued";
    private static final String DLT_TOPIC = ISSUE_TOPIC + ".DLT";

    private final AdminClient reconciliationKafkaAdminClient;

    @Override
    public KafkaSettlement check() {
        try {
            Set<String> topicNames =
                    reconciliationKafkaAdminClient
                            .listTopics()
                            .names()
                            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<TopicPartition> issuePartitions = topicPartitions(ISSUE_TOPIC);
            long lag = consumerLag(issuePartitions);
            long dltCount =
                    topicNames.contains(DLT_TOPIC)
                            ? endOffsetTotal(topicPartitions(DLT_TOPIC))
                            : 0L;
            return new KafkaSettlement(lag, dltCount);
        } catch (Exception exception) {
            throw new IllegalStateException("Kafka 정착 상태 확인에 실패했습니다.", exception);
        }
    }

    private List<TopicPartition> topicPartitions(String topic) throws Exception {
        return reconciliationKafkaAdminClient
                .describeTopics(List.of(topic))
                .topicNameValues()
                .get(topic)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .partitions()
                .stream()
                .map(partition -> new TopicPartition(topic, partition.partition()))
                .toList();
    }

    private long consumerLag(List<TopicPartition> partitions) throws Exception {
        Map<TopicPartition, OffsetAndMetadata> committedOffsets =
                reconciliationKafkaAdminClient
                        .listConsumerGroupOffsets("coupon-service")
                        .partitionsToOffsetAndMetadata()
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        Map<TopicPartition, Long> endOffsets = endOffsets(partitions);
        return partitions.stream()
                .mapToLong(
                        partition ->
                                Math.max(
                                        0L,
                                        endOffsets.get(partition)
                                                - committedOffsets
                                                        .getOrDefault(
                                                                partition,
                                                                new OffsetAndMetadata(0L))
                                                        .offset()))
                .sum();
    }

    private long endOffsetTotal(List<TopicPartition> partitions) throws Exception {
        return endOffsets(partitions).values().stream().mapToLong(Long::longValue).sum();
    }

    private Map<TopicPartition, Long> endOffsets(List<TopicPartition> partitions) throws Exception {
        Map<TopicPartition, OffsetSpec> request = new HashMap<>();
        for (TopicPartition partition : partitions) {
            request.put(partition, OffsetSpec.latest());
        }

        Map<TopicPartition, org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo>
                offsetResults =
                        reconciliationKafkaAdminClient
                                .listOffsets(request)
                                .all()
                                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        Map<TopicPartition, Long> offsets = new HashMap<>();
        for (Map.Entry<
                        TopicPartition,
                        org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo>
                entry : offsetResults.entrySet()) {
            offsets.put(entry.getKey(), entry.getValue().offset());
        }
        return offsets;
    }
}
