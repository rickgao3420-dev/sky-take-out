package com.sky.service.impl;

import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.UserMapper;
import com.sky.service.ReportService;
import com.sky.vo.OrderReportVO;
import com.sky.vo.SalesTop10ReportVO;
import com.sky.vo.TurnoverReportVO;
import com.sky.vo.UserReportVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 数据统计
 */
@Service
@Slf4j
public class ReportServiceImpl implements ReportService {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private UserMapper userMapper;

    /**
     * 营业额统计
     *
     * @param begin
     * @param end
     * @return
     */
    public TurnoverReportVO getTurnoverStatistics(LocalDate begin, LocalDate end) {
        List<LocalDate> dateList = getDateList(begin, end);

        // 一次分组查询取出每天的营业额，再按照日期列表补全没有订单的日期
        Map<LocalDate, BigDecimal> turnoverMap = new HashMap<>();
        List<Map<String, Object>> rows = orderMapper.sumAmountGroupByDate(beginTime(begin), endTime(end), Orders.COMPLETED);
        for (Map<String, Object> row : rows) {
            turnoverMap.put(parseDate(row.get("orderDate")), (BigDecimal) row.get("amount"));
        }

        List<String> turnoverList = new ArrayList<>();
        for (LocalDate date : dateList) {
            turnoverList.add(formatAmount(turnoverMap.get(date)));
        }

        return TurnoverReportVO.builder()
                .dateList(join(dateList))
                .turnoverList(join(turnoverList))
                .build();
    }

    /**
     * 用户统计
     *
     * @param begin
     * @param end
     * @return
     */
    public UserReportVO getUserStatistics(LocalDate begin, LocalDate end) {
        List<LocalDate> dateList = getDateList(begin, end);

        // 起始日期之前注册的用户数作为累计用户量的基数
        int totalUserCount = userMapper.countByCreateTimeBefore(beginTime(begin));

        // 一次分组查询取出每天的新增用户数
        Map<LocalDate, Integer> newUserMap = new HashMap<>();
        List<Map<String, Object>> rows = userMapper.countNewUserGroupByDate(beginTime(begin), endTime(end));
        for (Map<String, Object> row : rows) {
            newUserMap.put(parseDate(row.get("createDate")), toInt(row.get("newUserCount")));
        }

        // 用户总量是累加值，按日期逐天累加
        List<Integer> totalUserList = new ArrayList<>();
        List<Integer> newUserList = new ArrayList<>();
        for (LocalDate date : dateList) {
            int newUserCount = newUserMap.getOrDefault(date, 0);
            totalUserCount += newUserCount;
            newUserList.add(newUserCount);
            totalUserList.add(totalUserCount);
        }

        return UserReportVO.builder()
                .dateList(join(dateList))
                .newUserList(join(newUserList))
                .totalUserList(join(totalUserList))
                .build();
    }

    /**
     * 订单统计
     *
     * @param begin
     * @param end
     * @return
     */
    public OrderReportVO getOrderStatistics(LocalDate begin, LocalDate end) {
        List<LocalDate> dateList = getDateList(begin, end);

        // 一次分组查询取出每天的订单总数和有效订单数
        Map<LocalDate, Integer> orderCountMap = new HashMap<>();
        Map<LocalDate, Integer> validOrderCountMap = new HashMap<>();
        List<Map<String, Object>> rows = orderMapper.countGroupByDate(beginTime(begin), endTime(end), Orders.COMPLETED);
        for (Map<String, Object> row : rows) {
            LocalDate date = parseDate(row.get("orderDate"));
            orderCountMap.put(date, toInt(row.get("orderCount")));
            validOrderCountMap.put(date, toInt(row.get("validOrderCount")));
        }

        int totalOrderCount = 0;
        int validOrderCount = 0;
        List<Integer> orderCountList = new ArrayList<>();
        List<Integer> validOrderCountList = new ArrayList<>();
        for (LocalDate date : dateList) {
            int orderCount = orderCountMap.getOrDefault(date, 0);
            int validOrderCountOfDay = validOrderCountMap.getOrDefault(date, 0);
            orderCountList.add(orderCount);
            validOrderCountList.add(validOrderCountOfDay);
            totalOrderCount += orderCount;
            validOrderCount += validOrderCountOfDay;
        }

        // 订单完成率 = 有效订单数 / 订单总数，区间内没有订单时为0，避免出现除数为0
        double orderCompletionRate = totalOrderCount == 0 ? 0.0 : validOrderCount * 1.0 / totalOrderCount;

        return OrderReportVO.builder()
                .dateList(join(dateList))
                .orderCountList(join(orderCountList))
                .validOrderCountList(join(validOrderCountList))
                .totalOrderCount(totalOrderCount)
                .validOrderCount(validOrderCount)
                .orderCompletionRate(orderCompletionRate)
                .build();
    }

    /**
     * 销量排名统计
     *
     * @param begin
     * @param end
     * @return
     */
    public SalesTop10ReportVO getSalesTop10(LocalDate begin, LocalDate end) {
        List<Map<String, Object>> rows = orderMapper.getSalesTop10(beginTime(begin), endTime(end), Orders.COMPLETED);

        List<String> nameList = new ArrayList<>();
        List<Integer> saleCountList = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            nameList.add(String.valueOf(row.get("name")));
            saleCountList.add(toInt(row.get("saleCount")));
        }

        return SalesTop10ReportVO.builder()
                .nameList(join(nameList))
                .numberList(join(saleCountList))
                .build();
    }

    /**
     * 生成时间区间内的所有日期，包含开始和结束日期
     *
     * @param begin
     * @param end
     * @return
     */
    private List<LocalDate> getDateList(LocalDate begin, LocalDate end) {
        List<LocalDate> dateList = new ArrayList<>();
        for (LocalDate date = begin; !date.isAfter(end); date = date.plusDays(1)) {
            dateList.add(date);
        }
        return dateList;
    }

    /**
     * 查询区间的开始时间，即日期当天的0点
     *
     * @param begin
     * @return
     */
    private LocalDateTime beginTime(LocalDate begin) {
        return LocalDateTime.of(begin, LocalTime.MIN);
    }

    /**
     * 查询区间的结束时间，即日期后一天的0点
     * order_time是datetime类型，sql中统一用 < 结束时间 比较，组成左闭右开区间，
     * 这样既包含结束日期一整天的数据，也不会多统计后一天的数据
     *
     * @param end
     * @return
     */
    private LocalDateTime endTime(LocalDate end) {
        return LocalDateTime.of(end.plusDays(1), LocalTime.MIN);
    }

    /**
     * 把分组查询结果中的日期字符串转换为LocalDate
     *
     * @param value
     * @return
     */
    private LocalDate parseDate(Object value) {
        return LocalDate.parse(String.valueOf(value));
    }

    /**
     * 把分组查询结果中的数字转换为int，没有数据时为0
     *
     * @param value
     * @return
     */
    private int toInt(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    /**
     * 营业额保留两位小数，没有订单的日期为0.00
     *
     * @param amount
     * @return
     */
    private String formatAmount(BigDecimal amount) {
        return (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 把集合转换为以逗号分隔的字符串
     *
     * @param list
     * @return
     */
    private String join(List<?> list) {
        return list.stream().map(String::valueOf).collect(Collectors.joining(","));
    }
}
