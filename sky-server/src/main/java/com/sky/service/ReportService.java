package com.sky.service;

import com.sky.vo.OrderReportVO;
import com.sky.vo.SalesTop10ReportVO;
import com.sky.vo.TurnoverReportVO;
import com.sky.vo.UserReportVO;

import java.time.LocalDate;

public interface ReportService {

    /**
     * 统计时间区间内的营业额
     * @param begin 开始日期
     * @param end 结束日期
     * @return 日期列表和对应的营业额列表，以逗号分隔
     */
    TurnoverReportVO getTurnoverStatistics(LocalDate begin, LocalDate end);

    /**
     * 统计时间区间内的用户数量
     * @param begin 开始日期
     * @param end 结束日期
     * @return 日期列表、每日新增用户列表和用户总量列表，以逗号分隔
     */
    UserReportVO getUserStatistics(LocalDate begin, LocalDate end);

    /**
     * 统计时间区间内的订单数量
     * @param begin 开始日期
     * @param end 结束日期
     * @return 日期列表、每日订单数列表、每日有效订单数列表，以及订单总数、有效订单数和订单完成率
     */
    OrderReportVO getOrderStatistics(LocalDate begin, LocalDate end);

    /**
     * 统计时间区间内销量排名前10的菜品和套餐
     * @param begin 开始日期
     * @param end 结束日期
     * @return 商品名称列表和对应的销量列表，以逗号分隔
     */
    SalesTop10ReportVO getSalesTop10(LocalDate begin, LocalDate end);
}
