package com.eventplatform.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.eventplatform.dto.Result;
import com.eventplatform.entity.VoucherOrder;
import com.eventplatform.mapper.VoucherOrderMapper;
import com.eventplatform.order.OrderTransactions;
import com.eventplatform.service.IVoucherOrderService;
import com.eventplatform.utils.UserHolder;
import org.springframework.stereotype.Service;

@Service
@org.springframework.context.annotation.Profile("legacy-experiment")
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    private final OrderTransactions orders;
    public VoucherOrderServiceImpl(OrderTransactions orders) { this.orders = orders; }
    @Override public Result seckillVoucher(Long voucherId) {
        if (voucherId == null || voucherId <= 0) return Result.fail("Invalid voucher ID");
        return Result.ok(orders.reserve(UserHolder.getUser().getId(), voucherId));
    }
}
