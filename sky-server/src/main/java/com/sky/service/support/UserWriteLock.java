package com.sky.service.support;

import com.sky.context.BaseContext;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 所有用户购物车写操作/下单/默认地址先锁同一用户行；多实例也有效，无需改表。 */
@Component
@RequiredArgsConstructor
public class UserWriteLock {
    private final UserMapper userMapper;
    public Long lock() {
        Long id = BaseContext.getCurrentId();
        if (id == null || userMapper.lockById(id) == null) {
            throw new ShoppingCartBusinessException("用户不存在，请重新登录");
        }
        return id;
    }
}
