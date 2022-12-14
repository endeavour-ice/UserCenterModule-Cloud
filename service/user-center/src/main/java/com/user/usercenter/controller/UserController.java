package com.user.usercenter.controller;


import cn.hutool.core.util.StrUtil;
import com.user.model.constant.RedisKey;
import com.user.model.constant.UserStatus;
import com.user.model.domain.User;
import com.user.model.request.UpdateUserRequest;
import com.user.model.request.UserLoginRequest;
import com.user.model.request.UserRegisterRequest;
import com.user.model.request.UserSearchTagAndTxtRequest;
import com.user.rabbitmq.config.mq.RabbitService;
import com.user.usercenter.service.IUserService;
import com.user.util.common.B;
import com.user.util.common.ErrorCode;
import com.user.util.exception.GlobalException;
import com.user.util.utils.TimeUtils;
import com.user.util.utils.UserUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 缓存一致性 可以使用 Canal Java => https://blog.csdn.net/a56546/article/details/125170510
 * 数据库分库分表 读写分离 Mycat => https://blog.csdn.net/K_520_W/article/details/123702217
 * <p>
 * 用户表 前端控制器
 * </p>
 *
 * @author ice
 * @since 2022-06-14
 */
@RestController
@RequestMapping("/user")
@SuppressWarnings("all")
@Slf4j
public class UserController {

    @Resource
    private IUserService userService;

    @Autowired
    private RabbitService rabbitService;
    @Resource
    private RedisTemplate<String, Object> redisTemplate;


    // 用户注册
    @PostMapping("/Register")
    public B<Long> userRegister(@RequestBody UserRegisterRequest userRegister) {

        log.info("用户注册!!!!!!!!!!");
        if (userRegister == null) {
            throw new GlobalException(ErrorCode.NULL_ERROR);
        }
        Long aLong = userService.userRegister(userRegister);
        return B.ok(aLong);
    }

    /**
     * 获取当前的登录信息
     *
     * @return 返回用户
     */
    @GetMapping("/current")
    public B<User> getCurrent(HttpServletRequest request) {
        User currentUser = UserUtils.getLoginUser(request);
        if (currentUser == null) {
            throw new GlobalException(ErrorCode.NO_LOGIN);
        }
        String id = currentUser.getId();
        User user = userService.getById(id);
        if (user.getUserStatus().equals(UserStatus.LOCKING)) {
            throw new GlobalException(ErrorCode.NO_AUTH, "该用户以锁定...");
        }
        // 进行脱敏
        User safetyUser = userService.getSafetyUser(user);
        return B.ok(safetyUser);
    }

    // 用户登录
    @PostMapping("/Login")
    public B<User> userLogin(@RequestBody UserLoginRequest userLogin, HttpServletRequest request) {
        if (userLogin == null) {
            throw new GlobalException(ErrorCode.NULL_ERROR, "数据为空!");
        }
        String userAccount = userLogin.getUserAccount();
        String password = userLogin.getPassword();
        boolean hasEmpty = StrUtil.hasEmpty(userAccount, password);
        if (hasEmpty) {
            throw new GlobalException(ErrorCode.NULL_ERROR, "账号密码为空!");
        }
        User user = userService.userLogin(userAccount, password, request);
        return B.ok(user);
    }

    // 忘记密码
    @PostMapping("/forget")
    public B<Boolean> userForget(@RequestBody UserRegisterRequest registerRequest) {
        boolean is = userService.userForget(registerRequest);
        return B.ok(is);
    }
    // 查询用户
    @GetMapping("/searchUser")
    public B<Map<String, Object>> searchUser(@RequestParam(required = false) String username, @RequestParam(required = false) Long current, Long size, HttpServletRequest request) {
        Map<String, Object> user = userService.searchUser(request, username, current, size);
        // 通过stream 流的方式将列表里的每个user进行脱敏
        return B.ok(user);
    }

    // 管理员删除用户
    @PostMapping("/delete")
    public B<Boolean> deleteUser(@RequestBody Long id, HttpServletRequest request) {
        if (id <= 0) {
            return B.error(ErrorCode.NULL_ERROR);
        }
        boolean admin = userService.isAdmin(request);
        if (!admin) {
            return B.error(ErrorCode.NO_AUTH);
        }
        boolean removeById = userService.removeById(id);
        return B.ok(removeById);
    }

    /**
     * 修改用户
     *
     * @param user    要修改的数据
     * @param request
     * @return
     */
    @PostMapping("/UpdateUser")
    public B<Integer> UpdateUser(User user, HttpServletRequest request) {
        Integer is = userService.updateUser(user, request);
        return B.ok(is);
    }


    /**
     * 用户注销
     */
    @PostMapping("/Logout")
    public B<Integer> userLogout(HttpServletRequest request) {
        log.info("用户注销");
        if (request == null) {
            return B.error(ErrorCode.NULL_ERROR);
        }
        userService.userLogout(request);
        return B.ok();
    }


    @PostMapping("/search/tags/txt")
    public B<List<User>> getSearchUserTag(@RequestBody UserSearchTagAndTxtRequest userSearchTagAndTxtRequest) {

        List<User> userList = userService.searchUserTag(userSearchTagAndTxtRequest);
        return B.ok(userList);
    }

    /**
     * 修改用户
     */
    @PostMapping("/update")
    public B<Long> updateUserByID(@RequestBody UpdateUserRequest updateUser, HttpServletRequest request) {
        if (request == null) {
            throw new GlobalException(ErrorCode.NO_LOGIN);
        }
        if (updateUser == null) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = UserUtils.getLoginUser(request);

        User user = new User();
        BeanUtils.copyProperties(updateUser, user);
        long id = userService.getUserByUpdateID(loginUser, user);
        return B.ok(id);
    }

    /**
     * 主页展示数据
     */
    @GetMapping("/recommend")
    public B<Map<String, Object>> recommendUser(@RequestParam(required = false) long current, long size, HttpServletRequest request) {
        User loginUser = UserUtils.getLoginUser(request);
        Map<String, Object> userMap = (Map<String, Object>) redisTemplate.opsForValue().get(RedisKey.redisIndexKey);
        if (userMap != null) {
            return B.ok(userMap);
        }
        Map<String, Object> map = userService.selectPageIndexList(current, size);
        try {
            redisTemplate.opsForValue().set(RedisKey.redisIndexKey, map, TimeUtils.getRemainSecondsOneDay(new Date()), TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("缓存失败!!");
            log.error(e.getMessage());
        }
        return B.ok(map);
    }

    // 搜索用户
    @GetMapping("/searchUserName")
    public B<List<User>> searchUserName(@RequestParam(required = false) String friendUserName,
                                        HttpServletRequest request) {
        User user = UserUtils.getLoginUser(request);
        String userId = user.getId();
        List<User> friendList = userService.friendUserName(userId, friendUserName);
        if (friendList.size() == 0) {
            return B.error(ErrorCode.NULL_ERROR);
        }
        return B.ok(friendList);
    }

    /**
     * 根据单个标签搜索
     */
    @GetMapping("/searchUserTag")
    public B<List<User>> searchUserTag(@RequestParam("tag") String tag, HttpServletRequest request) {
        List<User> userList = userService.searchUserTag(tag, request);
        return B.ok(userList);
    }

    /**
     * 匹配用户
     *
     * @param num     推荐数量
     * @param request
     * @return
     */
    @GetMapping("/match")
    public B<List<User>> matchUsers(long num, HttpServletRequest request) {
        if (num <= 0 || num > 20) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR, "参数错误...");
        }
        List<User> userVos = userService.matchUsers(num, request);
        return B.ok(userVos);
    }
}
