package ai.weixiu.mapper;

import ai.weixiu.pojo.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 用户表 Mapper 接口
 * </p>
 *
 * @author author
 * @since 2026-04-08
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

}
