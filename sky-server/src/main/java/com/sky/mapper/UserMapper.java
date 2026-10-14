package com.sky.mapper;

import com.sky.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface UserMapper {
    @Select("select id from user where id = #{id} for update")
    Long lockById(Long id);

    /**
     * 根据openid查询用户
     * @param openid
     * @return
     */
    @Select("select * from user where openid = #{openid}")
    User getByOpenid(String openid);

    /**
     * 插入数据
     * @param user
     */
    void insert(User user);

    /**
     * 统计指定时间之前注册的用户数量，用于计算累计用户量的基数
     * @param time 时间（不包含）
     * @return
     */
    @Select("select count(*) from user where create_time < #{time}")
    Integer countByCreateTimeBefore(@Param("time") LocalDateTime time);

    /**
     * 统计时间区间内每天新增的用户数量，按日期升序
     * @param begin 开始时间（包含）
     * @param end 结束时间（不包含）
     * @return 每天一条记录，包含 createDate、newUserCount 两个字段
     */
    @Select("select date_format(create_time, '%Y-%m-%d') as createDate, count(*) as newUserCount " +
            "from user " +
            "where create_time >= #{begin} and create_time < #{end} " +
            "group by date_format(create_time, '%Y-%m-%d') " +
            "order by createDate")
    List<Map<String, Object>> countNewUserGroupByDate(@Param("begin") LocalDateTime begin,
                                                      @Param("end") LocalDateTime end);
}
