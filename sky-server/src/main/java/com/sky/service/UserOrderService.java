package com.sky.service;

import com.alibaba.fastjson.JSON;
import com.sky.context.BaseContext;
import com.sky.dto.OrdersPaymentDTO;
import com.sky.dto.OrdersSubmitDTO;
import com.sky.entity.*;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.*;
import com.sky.service.impl.ShoppingCartServiceImpl;
import com.sky.service.support.CartRules;
import com.sky.service.support.UserWriteLock;
import com.sky.vo.*;
import com.sky.websocket.WebSocketServer;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/** 用户专用入口；管理员依旧使用 OrderService，不套用用户归属限制。 */
@Service
@RequiredArgsConstructor
public class UserOrderService {
    private final OrderMapper orders;
    private final OrderDetailMapper details;
    private final AddressBookService addresses;
    private final ShoppingCartMapper carts;
    private final ShoppingCartServiceImpl cartService;
    private final UserWriteLock userWriteLock;
    private final CartRules rules;
    private final OrderService orderService;
    private final WebSocketServer sockets;
    @Value("${sky.order.delivery-fee:6}") private BigDecimal deliveryFee = new BigDecimal("6");
    @Value("${sky.order.pack-fee-per-item:1}") private int packFeePerItem = 1;

    public Orders owned(Long id) {
        Orders row = orders.getById(id);
        if (row == null || !Objects.equals(row.getUserId(), BaseContext.getCurrentId()))
            throw new OrderBusinessException("订单不存在或无权访问");
        return row;
    }

    public OrderVO detail(Long id) { owned(id); return orderService.details(id); }

    @Transactional
    public OrderSubmitVO submit(OrdersSubmitDTO dto) {
        Long userId = userWriteLock.lock(); rules.requireOpen(); validate(dto);
        AddressBook address = addresses.getById(dto.getAddressBookId());
        List<ShoppingCart> rows = carts.list(ShoppingCart.builder().userId(userId).build());
        if (rows == null || rows.isEmpty()) throw new OrderBusinessException("购物车为空，请勿重复下单");
        BigDecimal goods = BigDecimal.ZERO; int count = 0;
        for (ShoppingCart row : rows) {
            rules.quantity(row.getNumber()); rules.hydrate(row);
            count += row.getNumber(); goods = goods.add(row.getAmount().multiply(BigDecimal.valueOf(row.getNumber())));
        }
        if(count > 999) throw new OrderBusinessException("订单商品数量过多");
        Orders order = new Orders();
        BeanUtils.copyProperties(dto, order, "amount", "packAmount", "tablewareNumber");
        order.setAmount(goods.add(BigDecimal.valueOf((long)count * packFeePerItem)).add(deliveryFee));
        order.setPackAmount(count * packFeePerItem);
        order.setTablewareNumber(Integer.valueOf(1).equals(dto.getTablewareStatus()) ? count : dto.getTablewareNumber());
        order.setNumber(UUID.randomUUID().toString().replace("-", ""));
        order.setOrderTime(LocalDateTime.now()); order.setUserId(userId);
        order.setStatus(Orders.PENDING_PAYMENT); order.setPayStatus(Orders.UN_PAID);
        order.setConsignee(address.getConsignee()); order.setPhone(address.getPhone());
        order.setAddress(text(address.getProvinceName()) + text(address.getCityName()) + text(address.getDistrictName()) + text(address.getDetail()));
        orders.insert(order);
        List<OrderDetail> lines = new ArrayList<>();
        for (ShoppingCart row : rows) {
            OrderDetail line = new OrderDetail(); BeanUtils.copyProperties(row, line, "id");
            line.setOrderId(order.getId()); lines.add(line);
        }
        details.insertBatch(lines); carts.deleteByUserId(userId);
        return OrderSubmitVO.builder().id(order.getId()).orderNumber(order.getNumber()).orderAmount(order.getAmount()).orderTime(order.getOrderTime()).build();
    }

    private String text(String value) { return value == null ? "" : value; }
    private void validate(OrdersSubmitDTO dto) {
        if(dto.getAddressBookId() == null || dto.getPayMethod() != 1 ||
           dto.getDeliveryStatus() == null || (dto.getDeliveryStatus() != 0 && dto.getDeliveryStatus() != 1) ||
           dto.getTablewareStatus() == null || (dto.getTablewareStatus() != 0 && dto.getTablewareStatus() != 1))
            throw new OrderBusinessException("配送、支付或餐具参数不正确");
        if(dto.getTablewareNumber() == null) dto.setTablewareNumber(0);
        if(dto.getTablewareNumber() < 0 || dto.getTablewareNumber() > 99) throw new OrderBusinessException("餐具数量无效");
        if(dto.getRemark() != null && dto.getRemark().codePointCount(0, dto.getRemark().length()) > 100) throw new OrderBusinessException("备注最多 100 个字符");
        LocalDateTime now = LocalDateTime.now();
        if(dto.getDeliveryStatus() == 0) {
            LocalDateTime time = dto.getEstimatedDeliveryTime();
            if(time == null || time.isBefore(now.plusHours(1)) || time.toLocalDate().isAfter(now.toLocalDate().plusDays(1)) ||
               time.toLocalTime().isBefore(LocalTime.of(9, 0)) || time.toLocalTime().isAfter(LocalTime.of(23, 0)) ||
               time.getMinute() % 30 != 0 || time.getSecond() != 0)
                throw new OrderBusinessException("预约时间须为今天或明天 09:00—23:00 的半小时时段，至少提前一小时");
        } else dto.setEstimatedDeliveryTime(now.plusHours(1));
    }

    /** 本项目仅模拟支付；原支付 VO 保持兼容，不生成真实商户参数。 */
    @Transactional
    public OrderPaymentVO payment(OrdersPaymentDTO dto) {
        if(!Integer.valueOf(1).equals(dto.getPayMethod())) throw new OrderBusinessException("仅支持微信渠道演示支付");
        Orders row = orders.getByNumberAndUserId(dto.getOrderNumber(), BaseContext.getCurrentId());
        if(row == null) throw new OrderBusinessException("订单不存在或无权访问");
        if(Integer.valueOf(Orders.PAID).equals(row.getPayStatus()) && row.getStatus() >= 2 && row.getStatus() <= 5) return new OrderPaymentVO();
        LocalDateTime now = LocalDateTime.now();
        if(row.getStatus() != Orders.PENDING_PAYMENT || row.getPayStatus() != Orders.UN_PAID ||
           !row.getOrderTime().plusMinutes(15).isAfter(now)) throw new OrderBusinessException("订单已失效或不可支付");
        if(orders.payIfPending(row.getId(), BaseContext.getCurrentId(), now) != 1) throw new OrderBusinessException("订单状态已变化，请刷新");
        notifyAfterCommit(1, row);
        return new OrderPaymentVO();
    }

    @Transactional
    public void cancel(Long id) {
        Orders row = owned(id);
        if(row.getStatus() != Orders.PENDING_PAYMENT && row.getStatus() != Orders.TO_BE_CONFIRMED)
            throw new OrderBusinessException("商家接单后不能直接取消");
        Orders update = Orders.builder().id(id).status(Orders.CANCELLED).cancelReason("用户取消").cancelTime(LocalDateTime.now())
                .payStatus(row.getPayStatus() == Orders.PAID ? Orders.REFUND : Orders.UN_PAID).build();
        if(orders.updateExpected(update, row.getStatus()) != 1) throw new OrderBusinessException("订单状态已变化，请刷新");
    }

    @Transactional
    public void repeat(Long id) {
        Long userId = userWriteLock.lock(); owned(id); rules.requireOpen();
        List<OrderDetail> old = details.getByOrderId(id);
        if(old == null || old.isEmpty()) throw new OrderBusinessException("原订单没有商品");
        List<ShoppingCart> rebuilt = new ArrayList<>();
        for(OrderDetail line : old) {
            ShoppingCart row = new ShoppingCart(); BeanUtils.copyProperties(line, row, "id");
            row.setUserId(userId); rules.quantity(row.getNumber()); rules.hydrate(row); rebuilt.add(row);
        }
        // 全部验证后再合并；任何失效或超量都会回滚整个事务，保留原购物车。
        for(ShoppingCart row : rebuilt) cartService.merge(row, row.getNumber());
    }

    public void remind(Long id) {
        Orders row = owned(id);
        if(row.getStatus() < 2 || row.getStatus() > 4) throw new OrderBusinessException("当前订单状态不能催单");
        notifyAfterCommit(2, row);
    }

    private void notifyAfterCommit(int type, Orders row) {
        Map<String,Object> event = new HashMap<>();
        event.put("type",type); event.put("orderId",row.getId()); event.put("content","订单号：" + row.getNumber());
        Runnable send = () -> sockets.sendToAllClient(JSON.toJSONString(event));
        if(TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { send.run(); }
            });
        } else send.run();
    }
}
