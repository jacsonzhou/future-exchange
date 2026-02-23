package com.exchange.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.user.entity.TradingAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 交易账户Mapper
 */
@Mapper
public interface TradingAccountMapper extends BaseMapper<TradingAccount> {

    /**
     * 根据用户ID查询默认账户
     */
    @Select("SELECT * FROM t_trading_account WHERE user_id = #{userId} AND status = 1 LIMIT 1")
    TradingAccount selectDefaultByUserId(Long userId);

    /**
     * 查询用户的所有账户
     */
    @Select("SELECT * FROM t_trading_account WHERE user_id = #{userId} AND status = 1")
    List<TradingAccount> selectByUserId(Long userId);

    /**
     * 标记账户已初始化资金
     */
    @Update("UPDATE t_trading_account SET is_funded = 1 WHERE id = #{accountId}")
    int markAsFunded(@Param("accountId") Long accountId);
}
