package com.exchange.cfddealer.service;

import com.exchange.cfddealer.dto.ReferenceBookSnapshot;

public interface ReferencePricingService {

    ReferenceBookSnapshot getReferenceBook(String symbol);
}
