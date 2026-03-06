package com.exchange.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.user.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户Mapper
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 根据用户名查询用户
     */
    @Select("SELECT * FROM t_user WHERE username = #{username} AND deleted = 0")
    User selectByUsername(String username);

    /**
     * 检查用户名是否存在
     */
    @Select("SELECT COUNT(*) FROM t_user WHERE username = #{username} AND deleted = 0")
    int countByUsername(String username);

    /**
     * 检查邮箱是否已被其他用户占用
     */
    @Select("SELECT COUNT(*) FROM t_user WHERE email = #{email} AND deleted = 0 AND id <> #{excludeUserId}")
    int countByEmail(@Param("email") String email, @Param("excludeUserId") Long excludeUserId);

    /**
     * 检查手机号是否已被其他用户占用
     */
    @Select("SELECT COUNT(*) FROM t_user WHERE phone = #{phone} AND deleted = 0 AND id <> #{excludeUserId}")
    int countByPhone(@Param("phone") String phone, @Param("excludeUserId") Long excludeUserId);

    /**
     * 统计用户有效 API Key 数量
     */
    @Select("SELECT COUNT(*) FROM t_user_api_key WHERE user_id = #{userId} AND status = 1")
    int countActiveApiKeys(@Param("userId") Long userId);
}
