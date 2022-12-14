package com.user.usercenter.controller;

import com.user.model.request.UserLabelRequest;
import com.user.model.resp.UserLabelResponse;
import com.user.usercenter.service.IUserLabelService;
import com.user.util.common.B;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * <p>
 * 标签表 前端控制器
 * </p>
 *
 * @author ice
 * @since 2022-09-16
 */
@RestController
@RequestMapping("/userLabel")
public class UserLabelController {

    @Resource
    private IUserLabelService labelService;



    @PostMapping("/addUserLabel")
    public B<Boolean> addUserLabel(UserLabelRequest labelRequest, HttpServletRequest request) {
        boolean is= labelService.addUserLabel(labelRequest,request);
        if (!is) {
            return B.error();
        }
        return B.ok();
    }

    /**
     * 获取所有的标签
     * @param request
     * @return
     */
    @GetMapping("/getLabel")
    public B<List<String>> getLabel(HttpServletRequest request) {
        List<String> list= labelService.getLabel(request);
        return B.ok(list);
    }

    @GetMapping("/getUserLabel")
    public B<List<UserLabelResponse>> getUserLabel(HttpServletRequest request) {
        List<UserLabelResponse> list= labelService.getUserLabel(request);
        return B.ok(list);
    }

    @GetMapping("/delUserLabel")
    public B<Boolean> delUserLabel(@RequestParam("id")String id, HttpServletRequest request) {
        boolean isDelete= labelService.delUserLabel(id,request);
        if (!isDelete) {
            return B.error();
        }
        return B.ok();
    }
}
