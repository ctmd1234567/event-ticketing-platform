package com.eventplatform.listener;

import com.eventplatform.config.QueueConfig;
import com.eventplatform.order.OrderTransactions;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@org.springframework.context.annotation.Profile("legacy-experiment")
public class SeckillVoucherListener {
    private final OrderTransactions orders;
    public SeckillVoucherListener(OrderTransactions orders) { this.orders = orders; }
    @RabbitListener(queues = QueueConfig.QUEUE, containerFactory = "batchRabbitListenerContainerFactory")
    public void receive(List<String> orderIds) {
        orders.fulfillBatch(orderIds.stream().map(Long::parseLong).toList());
        // Spring AUTO acknowledgement happens after the proxied transactional method has committed.
    }
}
