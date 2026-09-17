package com.sky.task;

import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单定时任务类
 */
@Component
@Slf4j
public class OrderTask {

    @Autowired
    private OrderMapper orderMapper;

    /**
     * 处理超时未支付的订单：下单超过15分钟仍未支付的订单，自动取消
     */
    @Scheduled(cron = "0 * * * * ?") // 每分钟触发一次
    public void processTimeoutOrder() {
        log.info("定时处理超时订单：{}", LocalDateTime.now());

        LocalDateTime timeoutTime = LocalDateTime.now().minusMinutes(15);
        List<Orders> ordersList = orderMapper.getByStatusAndOrderTimeLT(Orders.PENDING_PAYMENT, timeoutTime);

        if (ordersList != null && !ordersList.isEmpty()) {
            for (Orders orders : ordersList) {
                Orders cancelOrder = Orders.builder()
                        .id(orders.getId())
                        .status(Orders.CANCELLED)
                        .cancelReason("订单超时，自动取消")
                        .cancelTime(LocalDateTime.now())
                        .build();
                orderMapper.update(cancelOrder);
            }
        }
    }

    /**
     * 处理一直处于派送中的订单：每天凌晨1点把前一天仍处于派送中的订单自动完成
     */
    @Scheduled(cron = "0 0 1 * * ?") // 每天凌晨1点触发一次
    public void processDeliveryOrder() {
        log.info("定时处理处于派送中的订单：{}", LocalDateTime.now());

        LocalDateTime deliveryTimeoutTime = LocalDateTime.now().minusMinutes(60);
        List<Orders> ordersList = orderMapper.getByStatusAndOrderTimeLT(Orders.DELIVERY_IN_PROGRESS, deliveryTimeoutTime);

        if (ordersList != null && !ordersList.isEmpty()) {
            for (Orders orders : ordersList) {
                Orders completedOrder = Orders.builder()
                        .id(orders.getId())
                        .status(Orders.COMPLETED)
                        .build();
                orderMapper.update(completedOrder);
            }
        }
    }
}
