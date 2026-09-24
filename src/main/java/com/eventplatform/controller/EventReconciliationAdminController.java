package com.eventplatform.controller;

import com.eventplatform.dto.Result;
import com.eventplatform.payment.EventReconciliationService;
import com.eventplatform.utils.UserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/reconciliation")
public class EventReconciliationAdminController {
    private final EventReconciliationService reconciliation;

    public EventReconciliationAdminController(EventReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @GetMapping
    public Result scan(@RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") long afterOrderId,
            @RequestParam(defaultValue = "0") long afterPaymentId,
            @RequestParam(defaultValue = "0") long afterUnknownId,
            @RequestParam(defaultValue = "") String afterUnknownKind,
            @RequestParam(defaultValue = "") String afterEventId) {
        return Result.ok(reconciliation.scan(limit, afterOrderId, afterPaymentId,
                afterUnknownId, afterUnknownKind, afterEventId));
    }

    @PostMapping("/payments/{paymentId}/late-refund")
    public Result repairMissingLateRefund(@PathVariable long paymentId) {
        return Result.ok(reconciliation.repairMissingLateRefund(paymentId, UserHolder.getUser().getId()));
    }
}
