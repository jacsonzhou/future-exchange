package com.exchange.marketmaker.dto.request;

import lombok.Data;

/**
 * 申请做市商请求
 */
@Data
public class ApplyMarketMakerRequest {

    private String companyName;
    private String contactName;
    private String contactEmail;
    private String contactPhone;
    private String licenseNo;
    private String otherExchanges;
    private Long monthlyVolume;
}
