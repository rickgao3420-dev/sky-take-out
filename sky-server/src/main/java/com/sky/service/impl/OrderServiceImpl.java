package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.context.BaseContext;
import com.sky.dto.*;
import com.sky.entity.OrderDetail;
import com.sky.entity.Orders;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.OrderMapper;
import com.sky.result.PageResult;
import com.sky.service.OrderService;
import com.sky.vo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {
    private final OrderMapper orderMapper;
    private final OrderDetailMapper orderDetailMapper;

    public PageResult pageQuery4User(int pageNum, int pageSize, Integer status) {
        if(pageNum < 1 || pageSize < 1 || pageSize > 100 || (status != null && (status < 1 || status > 6)))
            throw new OrderBusinessException("分页或订单状态参数不正确");
        OrdersPageQueryDTO query = new OrdersPageQueryDTO();
        query.setUserId(BaseContext.getCurrentId()); query.setStatus(status);
        PageHelper.startPage(pageNum, pageSize);
        Page<Orders> page = orderMapper.pageQuery(query);
        return pageResult(page);
    }

    /** 共享查询：归属校验在用户专用服务；商家可以查看全部用户订单。 */
    public OrderVO details(Long id) {
        Orders row = required(id);
        return view(row);
    }

    public PageResult conditionSearch(OrdersPageQueryDTO query) {
        PageHelper.startPage(query.getPage(), query.getPageSize());
        return pageResult(orderMapper.pageQuery(query));
    }

    private PageResult pageResult(Page<Orders> page) {
        List<OrderVO> rows = new ArrayList<>();
        if(page != null) for(Orders row : page) rows.add(view(row));
        return new PageResult(page == null ? 0 : page.getTotal(), rows);
    }

    private OrderVO view(Orders row) {
        List<OrderDetail> lines = orderDetailMapper.getByOrderId(row.getId());
        OrderVO view = new OrderVO(); BeanUtils.copyProperties(row, view);
        view.setOrderDetailList(lines);
        view.setOrderDishes(lines.stream().map(x -> x.getName() + "*" + x.getNumber() + ";").collect(Collectors.joining()));
        return view;
    }

    public OrderStatisticsVO statistics() {
        OrderStatisticsVO result = new OrderStatisticsVO();
        result.setToBeConfirmed(orderMapper.countStatus(Orders.TO_BE_CONFIRMED));
        result.setConfirmed(orderMapper.countStatus(Orders.CONFIRMED));
        result.setDeliveryInProgress(orderMapper.countStatus(Orders.DELIVERY_IN_PROGRESS));
        return result;
    }

    public void confirm(OrdersConfirmDTO dto) {
        transition(Orders.builder().id(dto.getId()).status(Orders.CONFIRMED).build(), Orders.TO_BE_CONFIRMED);
    }

    @Transactional
    public void rejection(OrdersRejectionDTO dto) {
        Orders row = required(dto.getId());
        if(row.getStatus() != Orders.TO_BE_CONFIRMED) throw new OrderBusinessException("当前订单不能拒单");
        Orders update = cancelled(row);
        update.setRejectionReason(dto.getRejectionReason());
        transition(update, row.getStatus());
    }

    @Transactional
    public void cancel(OrdersCancelDTO dto) {
        Orders row = required(dto.getId());
        if(row.getStatus() == Orders.COMPLETED || row.getStatus() == Orders.CANCELLED)
            throw new OrderBusinessException("已结束订单不能取消");
        Orders update = cancelled(row); update.setCancelReason(dto.getCancelReason());
        transition(update, row.getStatus());
    }

    public void delivery(Long id) {
        transition(Orders.builder().id(id).status(Orders.DELIVERY_IN_PROGRESS).build(), Orders.CONFIRMED);
    }

    public void complete(Long id) {
        transition(Orders.builder().id(id).status(Orders.COMPLETED).deliveryTime(LocalDateTime.now()).build(), Orders.DELIVERY_IN_PROGRESS);
    }

    private Orders required(Long id) {
        Orders row = orderMapper.getById(id);
        if(row == null) throw new OrderBusinessException("订单不存在");
        return row;
    }

    private Orders cancelled(Orders row) {
        return Orders.builder().id(row.getId()).status(Orders.CANCELLED).cancelTime(LocalDateTime.now())
                .payStatus(row.getPayStatus() == Orders.PAID ? Orders.REFUND : row.getPayStatus()).build();
    }

    private void transition(Orders update, Integer expected) {
        if(orderMapper.updateExpected(update, expected) != 1)
            throw new OrderBusinessException("订单状态已变化或不允许此操作，请刷新");
    }
}
