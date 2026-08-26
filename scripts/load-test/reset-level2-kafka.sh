#!/usr/bin/env bash

set -euo pipefail

ISSUE_TOPIC="coupon-issued"
DLT_TOPIC="coupon-issued.DLT"
CONSUMER_GROUP="coupon-service"
KAFKA_TOPICS="/opt/kafka/bin/kafka-topics.sh"
KAFKA_GROUPS="/opt/kafka/bin/kafka-consumer-groups.sh"

echo "WARNING: Stop every coupon application instance before resetting Kafka."
echo "This deletes and recreates ${ISSUE_TOPIC}, ${DLT_TOPIC}, and ${CONSUMER_GROUP} offsets."
confirmation="${LEVEL2_KAFKA_CONFIRM:-}"
if [[ -z "${confirmation}" ]]; then
    read -r -p "Type RESET_KAFKA to continue: " confirmation
fi

if [[ "${confirmation}" != "RESET_KAFKA" ]]; then
    echo "Cancelled. Kafka was not changed."
    exit 1
fi

docker compose up -d kafka

kafka_ready=false
for _ in {1..30}; do
    if docker exec coupon-kafka "${KAFKA_TOPICS}" \
        --bootstrap-server localhost:9092 --list >/dev/null 2>&1; then
        kafka_ready=true
        break
    fi

    echo "Waiting for Kafka..."
    sleep 2
done

if [[ "${kafka_ready}" != "true" ]]; then
    echo "Kafka did not become ready within 60 seconds." >&2
    exit 1
fi

if docker exec coupon-kafka "${KAFKA_GROUPS}" \
    --bootstrap-server localhost:9092 --list | grep -Fxq "${CONSUMER_GROUP}"; then
    docker exec coupon-kafka "${KAFKA_GROUPS}" \
        --bootstrap-server localhost:9092 --delete --group "${CONSUMER_GROUP}"
fi

for topic in "${ISSUE_TOPIC}" "${DLT_TOPIC}"; do
    if docker exec coupon-kafka "${KAFKA_TOPICS}" \
        --bootstrap-server localhost:9092 --list | grep -Fxq "${topic}"; then
        docker exec coupon-kafka "${KAFKA_TOPICS}" \
            --bootstrap-server localhost:9092 --delete --topic "${topic}"
    fi
done

topics_deleted=false
for _ in {1..30}; do
    current_topics="$(docker exec coupon-kafka "${KAFKA_TOPICS}" \
        --bootstrap-server localhost:9092 --list)"
    if ! grep -Fxq "${ISSUE_TOPIC}" <<< "${current_topics}" \
        && ! grep -Fxq "${DLT_TOPIC}" <<< "${current_topics}"; then
        topics_deleted=true
        break
    fi

    echo "Waiting for Kafka topic deletion..."
    sleep 1
done

if [[ "${topics_deleted}" != "true" ]]; then
    echo "Kafka topics were recreated or not deleted. Check that all consumers are stopped." >&2
    exit 1
fi

for topic in "${ISSUE_TOPIC}" "${DLT_TOPIC}"; do
    docker exec coupon-kafka "${KAFKA_TOPICS}" \
        --bootstrap-server localhost:9092 \
        --create \
        --topic "${topic}" \
        --partitions 3 \
        --replication-factor 1
done

docker exec coupon-kafka "${KAFKA_TOPICS}" \
    --bootstrap-server localhost:9092 --describe --topic "${ISSUE_TOPIC}"
docker exec coupon-kafka "${KAFKA_TOPICS}" \
    --bootstrap-server localhost:9092 --describe --topic "${DLT_TOPIC}"

echo "Level 2 Kafka reset completed."
