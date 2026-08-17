package com.sky.service.impl;

import com.sky.constant.MessageConstant;
import com.sky.constant.RedisConstant;
import com.sky.constant.StatusConstant;
import com.sky.exception.ShopStatusInvalidException;
import com.sky.service.ShopStatusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ShopStatusServiceImpl implements ShopStatusService {

    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * 设置店铺营业状态
     * @param status
     */
    @Override
    public void setStatus(Integer status) {
        //校验参数，仅允许 1（营业）或 0（打烊）
        if (!StatusConstant.ENABLE.equals(status) && !StatusConstant.DISABLE.equals(status)) {
            throw new ShopStatusInvalidException(MessageConstant.SHOP_STATUS_PARAM_ERROR);
        }
        redisTemplate.opsForValue().set(RedisConstant.SHOP_STATUS, status);
    }

    /**
     * 获取店铺营业状态
     * @return
     */
    @Override
    public Integer getStatus() {
        Integer status = (Integer) redisTemplate.opsForValue().get(RedisConstant.SHOP_STATUS);
        //兜底：Redis中不存在该key（如Redis重启或数据被清空）时，默认营业
        if (status == null) {
            status = StatusConstant.ENABLE;
            redisTemplate.opsForValue().set(RedisConstant.SHOP_STATUS, status);
        }
        return status;
    }
}
