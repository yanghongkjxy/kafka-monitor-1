package org.kairosdb.kafka.monitor;

import com.google.inject.Inject;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.BytesDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.utils.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Properties;

import static org.kairosdb.kafka.monitor.OffsetListenerService.OFFSET_TOPIC;

/**
 Reads offsets from the __consumer_offsets topic, filters and publishes them
 to a topic partitioned on topic name.  This is to group topic offsets so multiple
 monitors can process offsets for an entire topic
 */
public class RawOffsetReader extends TopicReader
{
	private static final Logger logger = LoggerFactory.getLogger(RawOffsetReader.class);

	private MonitorConfig m_monitorConfig;
	private final int m_instanceId;
	private final Properties m_consumerConfig;
	private final Properties m_producerConfig;

	private KafkaConsumer<Bytes, Bytes> m_consumer;
	private KafkaProducer<String, Offset> m_producer;


	@Inject
	public RawOffsetReader(@Named("DefaultConfig")Properties defaultConfig,
			MonitorConfig monitorConfig, int instanceId)
	{
		m_monitorConfig = monitorConfig;
		m_instanceId = instanceId;

		m_consumerConfig = (Properties) defaultConfig.clone();

		m_consumerConfig.put(ConsumerConfig.CLIENT_ID_CONFIG, m_monitorConfig.getClientId()+"_raw_"+m_instanceId);
		m_consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

		m_producerConfig = (Properties) m_consumerConfig.clone();

		m_consumerConfig.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, BytesDeserializer.class);
		m_consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, BytesDeserializer.class);

		m_producerConfig.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		m_producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, Offset.OffsetSerializer.class);
	}

	@Override
	protected void initializeConsumers()
	{
		m_consumer = new KafkaConsumer<>(m_consumerConfig);
		m_producer = new KafkaProducer<>(m_producerConfig);

		m_consumer.subscribe(Collections.singleton(OFFSET_TOPIC));
		m_consumer.seekToBeginning(m_consumer.assignment());
	}

	@Override
	protected void stopConsumers()
	{
		m_consumer.close();
		m_producer.close();
	}


	private boolean includeOffset(Bytes key, Bytes value)
	{
		if (key != null && value != null)
		{
			//This code only handles versions 0 and 1 of the offsets
			//version 2 appears to mean something else
			ByteBuffer bbkey = ByteBuffer.wrap(key.get());
			if (bbkey.getShort() > 1)
			{
				logger.debug("Unknown key {}", key);
				logger.debug("Unknown value: {}", value);
				return false;
			}

			ByteBuffer bbvalue = ByteBuffer.wrap(value.get());
			if (bbvalue.getShort() > 1)
			{
				logger.debug("Unknown value: {}", value);
				return false;
			}

			return true;
		}
		else
			return false;
	}


	@Override
	protected void readTopic()
	{
		ConsumerRecords<Bytes, Bytes> records = m_consumer.poll(100);

		long now = System.currentTimeMillis();

		for (ConsumerRecord<Bytes, Bytes> record : records)
		{
			if (includeOffset(record.key(), record.value()))
			{
				Offset offset = Offset.createFromBytes(record.key().get(), record.value().get());

				//todo check if our own offsets
				//todo !value.getTopic().equals(OFFSET_TOPIC)
				if (m_monitorConfig.isExcludeMonitorOffsets() && offset.getGroup().startsWith(m_monitorConfig.getApplicationId()))
				{
					continue;
				}

				//Filter out expired offsets.  We can still read them long after they have expired
				if (offset.getExpireTime() > now)
					m_producer.send(new ProducerRecord<String, Offset>(m_monitorConfig.getOffsetsTopicName(), offset.getTopic(), offset));
			}
		}
	}
}
