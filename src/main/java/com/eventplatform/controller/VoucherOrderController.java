package com.eventplatform.controller;

import com.eventplatform.dto.Result;
import com.eventplatform.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

@RestController
@RequestMapping("/voucher-order")
@org.springframework.context.annotation.Profile("legacy-experiment")
public class VoucherOrderController {
    @Resource
    private com.eventplatform.security.RequestLimits limits;
    @Resource
    private com.eventplatform.order.OrderTransactions orders;

    @org.springframework.web.bind.annotation.GetMapping("/{id}")
    public Result status(@PathVariable("id") Long id) {
        return Result.ok(orders.status(id, com.eventplatform.utils.UserHolder.getUser().getId()));
    }
    @Resource
    private IVoucherOrderService voucherOrderService;
    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        limits.order(com.eventplatform.utils.UserHolder.getUser().getId(), voucherId);
        return voucherOrderService.seckillVoucher(voucherId);
    }
}
