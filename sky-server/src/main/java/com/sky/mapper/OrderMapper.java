package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.dto.OrdersPageQueryDTO;
import com.sky.entity.Orders;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface OrderMapper {

    /**
     * 插入订单数据
     * @param orders
     */
    void insert(Orders orders);

    /**
     * 根据订单号和用户id查询订单
     * @param orderNumber
     * @param userId
     * @return
     */
    @Select("select * from orders where number = #{orderNumber} and user_id = #{userId}")
    Orders getByNumberAndUserId(String orderNumber, Long userId);

    /**
     * 动态修改订单信息
     * @param orders
     */
    void update(Orders orders);

    /**
     * 分页条件查询订单
     * @param ordersPageQueryDTO
     * @return
     */
    Page<Orders> pageQuery(OrdersPageQueryDTO ordersPageQueryDTO);

    /**
     * 根据id查询订单
     * @param id
     * @return
     */
    @Select("select * from orders where id = #{id}")
    Orders getById(Long id);

    /**
     * 根据状态和下单时间查询订单
     * @param status
     * @param orderTime
     * @return
     */
    @Select("select * from orders where status = #{status} and order_time < #{orderTime}")
    List<Orders> getByStatusAndOrderTimeLT(Integer status, LocalDateTime orderTime);

    /**
     * 根据状态统计订单数量
     * @param status
     * @return
     */
    @Select("select count(id) from orders where status = #{status}")
    Integer countStatus(Integer status);

    /**
     * 统计时间区间内每天的营业额，按日期升序
     * @param begin 开始时间（包含）
     * @param end 结束时间（不包含）
     * @param status 订单状态
     * @return 每天一条记录，包含 orderDate、amount 两个字段
     */
    List<Map<String, Object>> sumAmountGroupByDate(@Param("begin") LocalDateTime begin,
                                                   @Param("end") LocalDateTime end,
                                                   @Param("status") Integer status);

    /**
     * 统计时间区间内每天的订单数，按日期升序
     * @param begin 开始时间（包含）
     * @param end 结束时间（不包含）
     * @param status 有效订单的状态
     * @return 每天一条记录，包含 orderDate、orderCount、validOrderCount 三个字段
     */
    List<Map<String, Object>> countGroupByDate(@Param("begin") LocalDateTime begin,
                                               @Param("end") LocalDateTime end,
                                               @Param("status") Integer status);

    /**
     * 查询时间区间内销量排名前10的菜品和套餐，按销量降序
     * @param begin 开始时间（包含）
     * @param end 结束时间（不包含）
     * @param status 订单状态
     * @return 每条记录包含 name、saleCount 两个字段
     */
    List<Map<String, Object>> getSalesTop10(@Param("begin") LocalDateTime begin,
                                            @Param("end") LocalDateTime end,
                                            @Param("status") Integer status);
}
