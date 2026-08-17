package com.sky.service;

public interface ShopStatusService {

    /**
     * 设置店铺营业状态
     * @param status
     */
    void setStatus(Integer status);

    /**
     * 获取店铺营业状态
     * @return
     */
    Integer getStatus();
}
