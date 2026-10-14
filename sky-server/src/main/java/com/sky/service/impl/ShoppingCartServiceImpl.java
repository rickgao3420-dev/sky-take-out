package com.sky.service.impl;

import com.sky.context.BaseContext;
import com.sky.dto.ShoppingCartDTO;
import com.sky.entity.ShoppingCart;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.service.ShoppingCartService;
import com.sky.service.support.CartRules;
import com.sky.service.support.UserWriteLock;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ShoppingCartServiceImpl implements ShoppingCartService {
    private final ShoppingCartMapper shoppingCartMapper;
    private final UserWriteLock userWriteLock;
    private final CartRules rules;

    @Transactional
    public void addShoppingCart(ShoppingCartDTO dto) {
        Long userId = userWriteLock.lock();
        rules.requireOpen();
        ShoppingCart row = new ShoppingCart();
        BeanUtils.copyProperties(dto, row);
        row.setUserId(userId);
        rules.hydrate(row);
        merge(row, 1);
    }

    /** 调用者必须已在事务中锁定用户；再来一单复用此入口。 */
    public void merge(ShoppingCart row, int quantity) {
        rules.quantity(quantity);
        List<ShoppingCart> matches = shoppingCartMapper.list(ShoppingCart.builder().userId(row.getUserId()).build())
                .stream().filter(x -> rules.same(x, row)).collect(Collectors.toList());
        int total = quantity;
        for (ShoppingCart match : matches) { rules.quantity(match.getNumber()); total += match.getNumber(); }
        rules.quantity(total);
        row.setNumber(total);
        if (matches.isEmpty()) {
            row.setCreateTime(LocalDateTime.now());
            shoppingCartMapper.insert(row);
        } else {
            row.setId(matches.get(0).getId());
            shoppingCartMapper.updateSnapshot(row);
            // 兼容旧数据：将重复 SKU 归并到第一条。
            for (int i = 1; i < matches.size(); i++) shoppingCartMapper.deleteById(matches.get(i).getId());
        }
    }

    public List<ShoppingCart> showShoppingCart() {
        return shoppingCartMapper.list(ShoppingCart.builder().userId(BaseContext.getCurrentId()).build());
    }

    @Transactional
    public void cleanShoppingCart() { shoppingCartMapper.deleteByUserId(userWriteLock.lock()); }

    @Transactional
    public void subShoppingCart(ShoppingCartDTO dto) {
        Long userId = userWriteLock.lock();
        ShoppingCart key = new ShoppingCart(); BeanUtils.copyProperties(dto, key); rules.identity(key);
        List<ShoppingCart> rows = shoppingCartMapper.list(ShoppingCart.builder().userId(userId).build());
        // 下架商品仍允许移除；空口味只匹配空口味，不泛匹配。
        for (ShoppingCart row : rows) {
            if (rules.same(row, key)) {
                rules.quantity(row.getNumber());
                if (row.getNumber() == 1) shoppingCartMapper.deleteById(row.getId());
                else { row.setNumber(row.getNumber() - 1); shoppingCartMapper.updateNumberById(row); }
                return;
            }
        }
    }
}
