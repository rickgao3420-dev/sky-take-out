package com.sky.exception;

/**
 * 店铺营业状态参数不合法异常
 */
public class ShopStatusInvalidException extends BaseException {

    public ShopStatusInvalidException(){}

    public ShopStatusInvalidException(String msg){
        super(msg);
    }
}
