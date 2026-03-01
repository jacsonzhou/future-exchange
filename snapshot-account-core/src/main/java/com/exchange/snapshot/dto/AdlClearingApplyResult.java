package com.exchange.snapshot.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * ADL记账处理结果。
 */
@Data
public class AdlClearingApplyResult {

    private boolean success;
    private boolean idempotent;
    private String bizSeq;
    private String message;
    private List<String> ledgerIds = new ArrayList<>();
}
