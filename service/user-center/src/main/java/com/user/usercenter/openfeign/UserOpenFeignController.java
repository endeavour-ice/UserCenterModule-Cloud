package com.user.usercenter.openfeign;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.user.model.domain.User;
import com.user.usercenter.service.IUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @author ice
 * @date 2022/8/22 9:06
 */
@RestController
@RequestMapping("user/feign")
public class UserOpenFeignController {
    @Autowired
    private IUserService userService;
    //  List<User> users = userService.listByIds(list);
    @PostMapping("/lists")
    public List<User> getListByIds(@RequestBody List<String> ids) {
        return userService.listByIds(ids);
    }


    @GetMapping("/getUserById")
    public User getUserById(@RequestParam("id") String id) {
        return userService.getById(id);
    }


    @GetMapping("/seeUserEmail")
    public boolean seeUserEmail(@RequestParam("email")String email) {
        if (StringUtils.hasText(email)) {
            QueryWrapper<User> wrapper = new QueryWrapper<>();
            wrapper.eq("email", email);
            long count = userService.count(wrapper);
            return count == 1;

        }
        return false;
    }

    @GetMapping("/ForgetUserEmail")
    public User forgetUserEmail(@RequestParam("email")String email) {
        if (StringUtils.hasText(email)) {
            QueryWrapper<User> wrapper = new QueryWrapper<>();
            wrapper.select("user_account", "email");
            wrapper.eq("email", email);
            return userService.getOne(wrapper);
        }
        return null;
    }
}
