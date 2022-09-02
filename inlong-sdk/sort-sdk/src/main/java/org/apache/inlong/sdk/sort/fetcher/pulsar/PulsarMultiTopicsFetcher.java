/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.sdk.sort.fetcher.pulsar;

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.inlong.sdk.sort.api.ClientContext;
import org.apache.inlong.sdk.sort.api.Deserializer;
import org.apache.inlong.sdk.sort.api.Interceptor;
import org.apache.inlong.sdk.sort.api.MultiTopicsFetcher;
import org.apache.inlong.sdk.sort.api.Seeker;
import org.apache.inlong.sdk.sort.api.SeekerFactory;
import org.apache.inlong.sdk.sort.api.SortClientConfig;
import org.apache.inlong.sdk.sort.entity.InLongMessage;
import org.apache.inlong.sdk.sort.entity.InLongTopic;
import org.apache.inlong.sdk.sort.entity.MessageRecord;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Messages;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionInitialPosition;
import org.apache.pulsar.client.api.SubscriptionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MultiTopicsFetcher for pulsar. */
public class PulsarMultiTopicsFetcher extends MultiTopicsFetcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(PulsarMultiTopicsFetcher.class);
    private PulsarConsumer currentConsumer;
    private List<PulsarConsumer> toBeRemovedConsumers = new LinkedList<>();
    private PulsarClient pulsarClient;

    public PulsarMultiTopicsFetcher(
            List<InLongTopic> topics,
            ClientContext context,
            Interceptor interceptor,
            Deserializer deserializer,
            PulsarClient pulsarClient) {
        super(topics, context, interceptor, deserializer);
        this.pulsarClient = Preconditions.checkNotNull(pulsarClient);
    }

    @Override
    public boolean init() {
        Consumer<byte[]> newConsumer = createConsumer(onlineTopics.values());
        if (Objects.isNull(newConsumer)) {
            LOGGER.error("create new consumer is null");
            return false;
        }
        this.currentConsumer = new PulsarConsumer(newConsumer);
        InLongTopic firstTopic = onlineTopics.values().stream().findFirst().get();
        this.seeker = SeekerFactory.createPulsarSeeker(newConsumer, firstTopic);
        String threadName =
                String.format("sort_sdk_pulsar_multi_topic_fetch_thread_%d", this.hashCode());
        this.fetchThread = new Thread(new PulsarMultiTopicsFetcher.Fetcher(), threadName);
        this.fetchThread.start();
        this.executor.scheduleWithFixedDelay(
                this::clearRemovedConsumerList,
                context.getConfig().getCleanOldConsumerIntervalSec(),
                context.getConfig().getCleanOldConsumerIntervalSec(),
                TimeUnit.SECONDS);
        return true;
    }

    private void clearRemovedConsumerList() {
        long cur = System.currentTimeMillis();
        List<PulsarConsumer> newList = new LinkedList<>();
        toBeRemovedConsumers.forEach(
                consumer -> {
                    long diff = cur - consumer.getStopTime();
                    if (diff > context.getConfig().getCleanOldConsumerIntervalSec() * 1000L
                            || consumer.isEmpty()) {
                        try {
                            consumer.close();
                        } catch (PulsarClientException e) {
                            LOGGER.warn("got exception in close old consumer", e);
                        }
                        return;
                    }
                    newList.add(consumer);
                });
        LOGGER.info(
                "after clear old consumers, the old size is {}, current size is {}",
                toBeRemovedConsumers.size(),
                newList.size());
        this.toBeRemovedConsumers = newList;
    }

    private boolean updateAll(Collection<InLongTopic> newTopics) {
        if (CollectionUtils.isEmpty(newTopics)) {
            LOGGER.error("new topics is empty or null");
            return false;
        }
        // stop old;
        this.setStopConsume(true);
        this.currentConsumer.pause();
        // create new;
        Consumer<byte[]> newConsumer = createConsumer(newTopics);
        if (Objects.isNull(newConsumer)) {
            currentConsumer.resume();
            this.setStopConsume(false);
            LOGGER.error("create new consumer failed, use the old one");
            return false;
        }
        PulsarConsumer newConsumerWrapper = new PulsarConsumer(newConsumer);
        InLongTopic firstTopic = newTopics.stream().findFirst().get();
        final Seeker newSeeker = SeekerFactory.createPulsarSeeker(newConsumer, firstTopic);
        // save
        currentConsumer.setStopTime(System.currentTimeMillis());
        toBeRemovedConsumers.add(currentConsumer);
        // replace
        this.currentConsumer = newConsumerWrapper;
        this.seeker = newSeeker;
        this.interceptor.configure(firstTopic);
        this.onlineTopics =
                newTopics.stream().collect(Collectors.toMap(InLongTopic::getTopic, t -> t));
        // resume
        this.setStopConsume(false);
        return true;
    }

    private Consumer<byte[]> createConsumer(Collection<InLongTopic> newTopics) {
        if (CollectionUtils.isEmpty(newTopics)) {
            LOGGER.error("new topic is empty or null");
            return null;
        }
        try {
            SubscriptionInitialPosition position = SubscriptionInitialPosition.Latest;
            SortClientConfig.ConsumeStrategy offsetResetStrategy =
                    context.getConfig().getOffsetResetStrategy();
            if (offsetResetStrategy == SortClientConfig.ConsumeStrategy.earliest
                    || offsetResetStrategy
                            == SortClientConfig.ConsumeStrategy.earliest_absolutely) {
                LOGGER.info("the subscription initial position is earliest!");
                position = SubscriptionInitialPosition.Earliest;
            }

            List<String> topicNames =
                    newTopics.stream().map(InLongTopic::getTopic).collect(Collectors.toList());
            Consumer<byte[]> consumer =
                    pulsarClient
                            .newConsumer(Schema.BYTES)
                            .topics(topicNames)
                            .subscriptionName(context.getConfig().getSortTaskId())
                            .subscriptionType(SubscriptionType.Shared)
                            .startMessageIdInclusive()
                            .subscriptionInitialPosition(position)
                            .ackTimeout(context.getConfig().getAckTimeoutSec(), TimeUnit.SECONDS)
                            .receiverQueueSize(context.getConfig().getPulsarReceiveQueueSize())
                            .subscribe();
            LOGGER.info("create consumer for topics {}", topicNames);
            return consumer;
        } catch (Exception e) {
            LOGGER.error("failed to create pulsar consumer", e);
            return null;
        }
    }

    @Override
    public void ack(String msgOffset) throws Exception {
        if (StringUtils.isBlank(msgOffset)) {
            LOGGER.error("ack failed, msg offset should not be blank");
            return;
        }
        if (Objects.isNull(currentConsumer)) {
            LOGGER.error("ack failed, consumer is null");
            return;
        }
        // if this ack belongs to current consumer
        MessageId messageId = currentConsumer.getMessageId(msgOffset);
        if (!Objects.isNull(messageId)) {
            doAck(msgOffset, this.currentConsumer, messageId);
            return;
        }

        // if this ack doesn't belong to current consumer, find in to be removed ones.
        for (PulsarConsumer oldConsumer : toBeRemovedConsumers) {
            MessageId id = oldConsumer.getMessageId(msgOffset);
            if (Objects.isNull(id)) {
                continue;
            }
            doAck(msgOffset, oldConsumer, id);
            LOGGER.info("ack an old consumer message");
            return;
        }
        context.getDefaultStateCounter().addAckFailTimes(1L);
        LOGGER.error("in pulsar multi topic fetcher, messageId == null");
    }

    private void doAck(String msgOffset, PulsarConsumer consumer, MessageId messageId) {
        if (!consumer.isConnected()) {
            return;
        }
        InLongTopic topic = consumer.getTopic(msgOffset);
        consumer.acknowledgeAsync(messageId)
                .thenAccept(ctx -> ackSucc(msgOffset, topic, this.currentConsumer))
                .exceptionally(
                        exception -> {
                            LOGGER.error(
                                    "topic "
                                            + topic
                                            + " ack failed for offset "
                                            + msgOffset
                                            + ", error: ",
                                    exception);
                            context.getStateCounterByTopic(topic).addAckFailTimes(1L);
                            return null;
                        });
    }

    private void ackSucc(String offset, InLongTopic topic, PulsarConsumer consumer) {
        consumer.remove(offset);
        context.getStateCounterByTopic(topic).addAckSuccTimes(1L);
    }

    @Override
    public void pause() {
        if (Objects.nonNull(currentConsumer)) {
            currentConsumer.pause();
        }
    }

    @Override
    public void resume() {
        if (Objects.nonNull(currentConsumer)) {
            currentConsumer.resume();
        }
    }

    @Override
    public boolean close() {
        mainLock.writeLock().lock();
        try {
            this.setStopConsume(true);
            LOGGER.info("closed online topics {}", onlineTopics);
            try {
                if (currentConsumer != null) {
                    currentConsumer.close();
                }
                if (fetchThread != null) {
                    fetchThread.interrupt();
                }
            } catch (PulsarClientException e) {
                LOGGER.warn("close pulsar client: ", e);
            } catch (Throwable t) {
                LOGGER.warn("got exception in close multi topic fetcher: ", t);
            }
            toBeRemovedConsumers.stream()
                    .filter(Objects::nonNull)
                    .forEach(
                            c -> {
                                try {
                                    c.close();
                                } catch (PulsarClientException e) {
                                    LOGGER.warn("close pulsar client: ", e);
                                }
                            });
            toBeRemovedConsumers.clear();
            return true;
        } finally {
            this.closed = true;
            mainLock.writeLock().unlock();
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void setStopConsume(boolean stopConsume) {
        this.stopConsume = stopConsume;
    }

    @Override
    public boolean isStopConsume() {
        return stopConsume;
    }

    @Override
    public List<InLongTopic> getTopics() {
        return new ArrayList<>(onlineTopics.values());
    }

    @Override
    public boolean updateTopics(List<InLongTopic> topics) {
        if (needUpdate(topics)) {
            return updateAll(topics);
        }
        LOGGER.info("no need to update multi topic fetcher");
        return false;
    }

    public class Fetcher implements Runnable {

        /**
         * put the received msg to onFinished method
         *
         * @param messageRecords {@link List}
         */
        private void handleAndCallbackMsg(List<MessageRecord> messageRecords) {
            long start = System.currentTimeMillis();
            try {
                context.getDefaultStateCounter().addCallbackTimes(1L);
                context.getConfig().getCallback().onFinishedBatch(messageRecords);
                context.getDefaultStateCounter()
                        .addCallbackTimeCost(System.currentTimeMillis() - start)
                        .addCallbackDoneTimes(1L);
            } catch (Exception e) {
                context.getDefaultStateCounter().addCallbackErrorTimes(1L);
                LOGGER.error("failed to handle callback: ", e);
            }
        }

        private String getOffset(MessageId msgId) {
            return Base64.getEncoder().encodeToString(msgId.toByteArray());
        }

        private void processPulsarMsg(Messages<byte[]> messages) throws Exception {
            for (Message<byte[]> msg : messages) {
                String topicName = msg.getTopicName();
                InLongTopic topic = onlineTopics.get(topicName);
                if (Objects.isNull(topic)) {
                    LOGGER.error("got a message with topic {}, which is not subscribe", topicName);
                    continue;
                }
                // if need seek
                if (msg.getPublishTime() < seeker.getSeekTime()) {
                    seeker.seek();
                    break;
                }
                String offsetKey = getOffset(msg.getMessageId());
                currentConsumer.put(offsetKey, topic, msg.getMessageId());

                // deserialize
                List<InLongMessage> inLongMessages =
                        deserializer.deserialize(
                                context, topic, msg.getProperties(), msg.getData());
                // intercept
                inLongMessages = interceptor.intercept(inLongMessages);
                if (inLongMessages.isEmpty()) {
                    ack(offsetKey);
                    continue;
                }
                List<MessageRecord> msgs = new ArrayList<>();
                msgs.add(
                        new MessageRecord(
                                topic.getTopicKey(),
                                inLongMessages,
                                offsetKey,
                                System.currentTimeMillis()));
                context.getStateCounterByTopic(topic).addConsumeSize(msg.getData().length);
                context.getStateCounterByTopic(topic).addMsgCount(msgs.size());
                handleAndCallbackMsg(msgs);
            }
        }

        @Override
        public void run() {
            boolean hasPermit;
            while (true) {
                hasPermit = false;
                try {
                    if (context.getConfig().isStopConsume() || stopConsume) {
                        TimeUnit.MILLISECONDS.sleep(50);
                        continue;
                    }

                    if (sleepTime > 0) {
                        TimeUnit.MILLISECONDS.sleep(sleepTime);
                    }

                    context.acquireRequestPermit();
                    hasPermit = true;
                    context.getDefaultStateCounter().addMsgCount(1L).addFetchTimes(1L);

                    long startFetchTime = System.currentTimeMillis();
                    Messages<byte[]> pulsarMessages = currentConsumer.batchReceive();

                    context.getDefaultStateCounter()
                            .addFetchTimeCost(System.currentTimeMillis() - startFetchTime);
                    if (null != pulsarMessages && pulsarMessages.size() != 0) {
                        processPulsarMsg(pulsarMessages);
                        sleepTime = 0L;
                    } else {
                        context.getDefaultStateCounter().addEmptyFetchTimes(1L);
                        emptyFetchTimes++;
                        if (emptyFetchTimes >= context.getConfig().getEmptyPollTimes()) {
                            sleepTime =
                                    Math.min(
                                            (sleepTime +=
                                                    context.getConfig().getEmptyPollSleepStepMs()),
                                            context.getConfig().getMaxEmptyPollSleepMs());
                            emptyFetchTimes = 0;
                        }
                    }
                } catch (Exception e) {
                    context.getDefaultStateCounter().addFetchErrorTimes(1L);
                    LOGGER.error("failed to fetch msg ", e);
                } finally {
                    if (hasPermit) {
                        context.releaseRequestPermit();
                    }
                }

                if (closed) {
                    break;
                }
            }
        }
    }
}
