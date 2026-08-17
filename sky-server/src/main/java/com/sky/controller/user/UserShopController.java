package com.sky.controller.user;

import com.sky.result.Result;
import com.sky.service.ShopStatusService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/shop")
@Api(tags = "店铺相关接口")
@Slf4j
public class UserShopController {

    @Autowired
    private ShopStatusService shopStatusService;

    /**
     * 获取店铺营业状态
     * @return
     */
    @GetMapping("/status")
    @ApiOperation("获取店铺营业状态")
    public Result<Integer> getStatus() {
        Integer status = shopStatusService.getStatus();
        log.info("获取店铺营业状态为：{}", status == 1 ? "营业" : "打烊");
        return Result.success(status);
    }
}
