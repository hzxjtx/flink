/*
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
package org.apache.flink.streaming.connectors.kinesis.manualtests;

import com.amazonaws.services.kinesis.AmazonKinesisClient;
import com.amazonaws.services.kinesis.model.DescribeStreamResult;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.configuration.ConfigConstants;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.connectors.kinesis.config.KinesisConfigConstants;
import org.apache.flink.streaming.connectors.kinesis.testutils.ExactlyOnceValidatingConsumerThread;
import org.apache.flink.streaming.connectors.kinesis.testutils.KinesisEventsGeneratorProducerThread;
import org.apache.flink.streaming.connectors.kinesis.util.AWSUtil;
import org.apache.flink.test.util.ForkableFlinkMiniCluster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * This test first starts a data generator, producing data into kinesis.
 * Then, it starts a consuming topology, ensuring that all records up to a certain
 * point have been seen.
 *
 * Invocation:
 * --region eu-central-1 --accessKey XXXXXXXXXXXX --secretKey XXXXXXXXXXXXXXXX
 */
public class ManualExactlyOnceTest {

	private static final Logger LOG = LoggerFactory.getLogger(ManualExactlyOnceTest.class);

	static final int TOTAL_EVENT_COUNT = 1000; // the producer writes one per 10 ms, so it runs for 10k ms = 10 seconds

	public static void main(String[] args) throws Exception {
		final ParameterTool pt = ParameterTool.fromArgs(args);
		LOG.info("Starting exactly once test");

		final String streamName = "flink-test-" + UUID.randomUUID().toString();
		final String accessKey = pt.getRequired("accessKey");
		final String secretKey = pt.getRequired("secretKey");
		final String region = pt.getRequired("region");

		Properties configProps = new Properties();
		configProps.setProperty(KinesisConfigConstants.CONFIG_AWS_CREDENTIALS_PROVIDER_BASIC_ACCESSKEYID, accessKey);
		configProps.setProperty(KinesisConfigConstants.CONFIG_AWS_CREDENTIALS_PROVIDER_BASIC_SECRETKEY, secretKey);
		configProps.setProperty(KinesisConfigConstants.CONFIG_AWS_REGION, region);
		AmazonKinesisClient client = AWSUtil.createKinesisClient(configProps);

		// create a stream for the test:
		client.createStream(streamName, 1);

		// wait until stream has been created
		DescribeStreamResult status = client.describeStream(streamName);
		LOG.info("status {}" ,status);
		while(!status.getStreamDescription().getStreamStatus().equals("ACTIVE")) {
			status = client.describeStream(streamName);
			LOG.info("Status of stream {}", status);
			Thread.sleep(1000);
		}

		final Configuration flinkConfig = new Configuration();
		flinkConfig.setInteger(ConfigConstants.LOCAL_NUMBER_TASK_MANAGER, 1);
		flinkConfig.setInteger(ConfigConstants.TASK_MANAGER_NUM_TASK_SLOTS, 8);
		flinkConfig.setInteger(ConfigConstants.TASK_MANAGER_MEMORY_SIZE_KEY, 16);
		flinkConfig.setString(ConfigConstants.RESTART_STRATEGY_FIXED_DELAY_DELAY, "0 s");

		ForkableFlinkMiniCluster flink = new ForkableFlinkMiniCluster(flinkConfig, false);
		flink.start();

		final int flinkPort = flink.getLeaderRPCPort();

		try {
			final AtomicReference<Throwable> producerError = new AtomicReference<>();
			Thread producerThread = KinesisEventsGeneratorProducerThread.create(
				TOTAL_EVENT_COUNT, 2,
				accessKey, secretKey, region, streamName,
				producerError, flinkPort, flinkConfig);
			producerThread.start();

			final AtomicReference<Throwable> consumerError = new AtomicReference<>();
			Thread consumerThread = ExactlyOnceValidatingConsumerThread.create(
				TOTAL_EVENT_COUNT, 200, 2, 500, 500,
				accessKey, secretKey, region, streamName,
				consumerError, flinkPort, flinkConfig);
			consumerThread.start();

			boolean deadlinePassed = false;
			long deadline = System.currentTimeMillis() + (1000 * 2 * 60); // wait at most for two minutes
			// wait until both producer and consumer finishes, or an unexpected error is thrown
			while ((consumerThread.isAlive() || producerThread.isAlive()) &&
				(producerError.get() == null && consumerError.get() == null)) {
				Thread.sleep(1000);
				if (System.currentTimeMillis() >= deadline) {
					LOG.warn("Deadline passed");
					deadlinePassed = true;
					break; // enough waiting
				}
			}

			if (producerThread.isAlive()) {
				producerThread.interrupt();
			}

			if (consumerThread.isAlive()) {
				consumerThread.interrupt();
			}

			if (producerError.get() != null) {
				LOG.info("+++ TEST failed! +++");
				throw new RuntimeException("Producer failed", producerError.get());
			}
			if (consumerError.get() != null) {
				LOG.info("+++ TEST failed! +++");
				throw new RuntimeException("Consumer failed", consumerError.get());
			}

			if (!deadlinePassed) {
				LOG.info("+++ TEST passed! +++");
			} else {
				LOG.info("+++ TEST failed! +++");
			}

		} finally {
			client.deleteStream(streamName);
			client.shutdown();

			// stopping flink
			flink.stop();
		}
	}
}
