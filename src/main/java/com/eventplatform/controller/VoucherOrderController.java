package com.eventplatform.controller;

import com.eventplatform.dto.Result;
import com.eventplatform.order.OrderTransactions;
import com.eventplatform.security.RequestLimits;
import com.eventplatform.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/voucher-order")
@Profile("legacy-experiment")
public class VoucherOrderController {
    @Resource
    private RequestLimits limits;

    @Resource
    private OrderTransactions orders;

    @GetMapping("/{id}")
    public Result status(@PathVariable("id") Long id) {
        return Result.ok(orders.status(id, UserHolder.getUser().getId()));
    }

    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        if (voucherId == null || voucherId <= 0) return Result.fail("Invalid voucher ID");
        long userId = UserHolder.getUser().getId();
        limits.order(userId, voucherId);
        return Result.ok(orders.reserve(userId, voucherId));
    }
}
