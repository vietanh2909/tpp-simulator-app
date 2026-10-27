package com.tpp.simulator.ledger;

import java.util.List;

/** Giao dịch kèm các lần nhận, cũ nhất trước (BR5.3). */
public record TransactionDetail(TransactionSummary transaction, List<CallbackDetail> callbacks) {}
