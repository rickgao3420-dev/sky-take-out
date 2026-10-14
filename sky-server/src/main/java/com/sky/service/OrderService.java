package com.sky.service;
import com.sky.dto.*;
import com.sky.result.PageResult;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderVO;

/** 商家操作与共享查询。用户写操作由 UserOrderService 承担。 */
public interface OrderService {
    PageResult pageQuery4User(int pageNum, int pageSize, Integer status);
    OrderVO details(Long id);
    PageResult conditionSearch(OrdersPageQueryDTO dto);
    OrderStatisticsVO statistics();
    void confirm(OrdersConfirmDTO dto);
    void rejection(OrdersRejectionDTO dto) throws Exception;
    void cancel(OrdersCancelDTO dto) throws Exception;
    void delivery(Long id);
    void complete(Long id);
}
