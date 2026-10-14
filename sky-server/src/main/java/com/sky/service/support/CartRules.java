package com.sky.service.support;

import com.alibaba.fastjson.JSON;
import com.sky.entity.*;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.*;
import com.sky.service.ShopStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class CartRules {
    private final DishMapper dishMapper;
    private final SetmealMapper setmealMapper;
    private final DishFlavorMapper flavorMapper;
    private final ShopStatusService shopStatusService;

    public void requireOpen() {
        if (!Integer.valueOf(1).equals(shopStatusService.getStatus())) {
            throw new ShoppingCartBusinessException("店铺休息中，暂不能点餐");
        }
    }

    public void identity(ShoppingCart row) {
        if ((row.getDishId() == null) == (row.getSetmealId() == null)) {
            throw new ShoppingCartBusinessException("菜品和套餐必须且只能选择一项");
        }
        if ((row.getDishId() != null && row.getDishId() <= 0) ||
            (row.getSetmealId() != null && row.getSetmealId() <= 0)) {
            throw new ShoppingCartBusinessException("商品编号无效");
        }
        row.setDishFlavor(row.getDishFlavor() == null ? "" : row.getDishFlavor());
    }

    /** 从当前商品重建快照，不信任历史价格和前端商品字段。 */
    public void hydrate(ShoppingCart row) {
        identity(row);
        if (row.getDishId() != null) {
            Dish dish = dishMapper.getById(row.getDishId());
            if (dish == null || !Integer.valueOf(1).equals(dish.getStatus())) {
                throw new ShoppingCartBusinessException("菜品已下架或不存在，请调整购物车");
            }
            List<DishFlavor> groups = flavorMapper.getByDishId(dish.getId());
            String[] selected = row.getDishFlavor().isEmpty() ? new String[0] : row.getDishFlavor().split(",", -1);
            if (groups.size() != selected.length) throw new ShoppingCartBusinessException("菜品规格已变更，请重新选择");
            for (int i = 0; i < groups.size(); i++) {
                List<String> options;
                try { options = JSON.parseArray(groups.get(i).getValue(), String.class); }
                catch (RuntimeException ex) { throw new ShoppingCartBusinessException("菜品规格配置无效，请联系商家"); }
                if (options == null || !options.contains(selected[i])) throw new ShoppingCartBusinessException("菜品规格已失效，请重新选择");
            }
            row.setName(dish.getName()); row.setImage(dish.getImage()); row.setAmount(dish.getPrice());
        } else {
            Setmeal setmeal = setmealMapper.getById(row.getSetmealId());
            if (setmeal == null || !Integer.valueOf(1).equals(setmeal.getStatus())) {
                throw new ShoppingCartBusinessException("套餐已下架或不存在，请调整购物车");
            }
            if (!row.getDishFlavor().isEmpty()) throw new ShoppingCartBusinessException("套餐不支持菜品口味");
            row.setName(setmeal.getName()); row.setImage(setmeal.getImage()); row.setAmount(setmeal.getPrice());
        }
        if (row.getAmount() == null || row.getAmount().compareTo(BigDecimal.ZERO) < 0) throw new ShoppingCartBusinessException("商品价格无效");
    }

    public boolean same(ShoppingCart a, ShoppingCart b) {
        return Objects.equals(a.getDishId(), b.getDishId()) && Objects.equals(a.getSetmealId(), b.getSetmealId()) &&
            Objects.equals(a.getDishFlavor() == null ? "" : a.getDishFlavor(), b.getDishFlavor() == null ? "" : b.getDishFlavor());
    }

    public void quantity(Integer n) {
        if (n == null || n < 1 || n > 99) throw new ShoppingCartBusinessException("每种规格数量须为 1 至 99");
    }
}
