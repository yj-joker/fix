package ai.weixiu.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE = "memory.exchange";
    public static final String DLX_EXCHANGE = "memory.dlx";

    public static final String REALTIME_QUEUE = "memory.realtime.queue";
    public static final String CONSOLIDATE_QUEUE = "memory.consolidate.queue";
    public static final String RESULT_QUEUE = "memory.result.queue";
    public static final String DLX_QUEUE = "memory.dlx.queue";

    // ===== 知识导入队列 =====
    public static final String KNOWLEDGE_EXCHANGE = "knowledge.exchange";
    public static final String KNOWLEDGE_IMPORT_QUEUE = "knowledge.import.queue";
    public static final String KNOWLEDGE_IMPORT_KEY = "knowledge.import";
    public static final String KNOWLEDGE_RESULT_QUEUE = "knowledge.result.queue";
    public static final String KNOWLEDGE_RESULT_KEY = "knowledge.result";

    public static final String REALTIME_KEY = "memory.realtime";
    public static final String CONSOLIDATE_KEY = "memory.consolidate";
    public static final String RESULT_KEY = "memory.result";

    // ===== Dead Letter Exchange =====

    @Bean
    public FanoutExchange dlxExchange() {
        return new FanoutExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue dlxQueue() {
        return QueueBuilder.durable(DLX_QUEUE).build();
    }

    @Bean
    public Binding dlxBinding() {
        return BindingBuilder.bind(dlxQueue()).to(dlxExchange());
    }

    // ===== Main Exchange =====

    @Bean
    public TopicExchange memoryExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    // ===== Realtime Queue (TTL 5min) =====

    @Bean
    public Queue realtimeQueue() {
        return QueueBuilder.durable(REALTIME_QUEUE)
                .withArgument("x-message-ttl", 300_000)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .build();
    }

    @Bean
    public Binding realtimeBinding() {
        return BindingBuilder.bind(realtimeQueue()).to(memoryExchange()).with(REALTIME_KEY);
    }

    // ===== Consolidate Queue (TTL 10min) =====

    @Bean
    public Queue consolidateQueue() {
        return QueueBuilder.durable(CONSOLIDATE_QUEUE)
                .withArgument("x-message-ttl", 600_000)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .build();
    }

    @Bean
    public Binding consolidateBinding() {
        return BindingBuilder.bind(consolidateQueue()).to(memoryExchange()).with(CONSOLIDATE_KEY);
    }

    // ===== Result Queue (Python → Java) =====

    @Bean
    public Queue resultQueue() {
        return QueueBuilder.durable(RESULT_QUEUE).build();
    }

    @Bean
    public Binding resultBinding() {
        return BindingBuilder.bind(resultQueue()).to(memoryExchange()).with(RESULT_KEY);
    }

    // ===== Knowledge Import Exchange & Queues =====

    @Bean
    public TopicExchange knowledgeExchange() {
        return new TopicExchange(KNOWLEDGE_EXCHANGE, true, false);
    }

    /** 知识导入任务队列（TTL 30min，PDF 解析+向量化耗时较长） */
    @Bean
    public Queue knowledgeImportQueue() {
        return QueueBuilder.durable(KNOWLEDGE_IMPORT_QUEUE)
                .withArgument("x-message-ttl", 1_800_000)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .build();
    }

    @Bean
    public Binding knowledgeImportBinding() {
        return BindingBuilder.bind(knowledgeImportQueue()).to(knowledgeExchange()).with(KNOWLEDGE_IMPORT_KEY);
    }

    /** 知识导入结果队列（Python → Java） */
    @Bean
    public Queue knowledgeResultQueue() {
        return QueueBuilder.durable(KNOWLEDGE_RESULT_QUEUE).build();
    }

    @Bean
    public Binding knowledgeResultBinding() {
        return BindingBuilder.bind(knowledgeResultQueue()).to(knowledgeExchange()).with(KNOWLEDGE_RESULT_KEY);
    }

    // ===== Serialization =====

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        template.setConfirmCallback((data, ack, cause) -> {
            if (!ack) {
                // publisher-confirm 失败时仅记录日志，降级由发送端处理
                org.slf4j.LoggerFactory.getLogger(RabbitMQConfig.class)
                        .warn("MQ 消息发送确认失败: {}", cause);
            }
        });
        return template;
    }
}
