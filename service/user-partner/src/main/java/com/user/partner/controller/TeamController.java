package com.user.partner.controller;

import com.user.model.domain.Team;
import com.user.model.domain.User;
import com.user.model.domain.vo.TeamUserVo;
import com.user.model.dto.TeamQuery;
import com.user.model.request.TeamAddRequest;
import com.user.model.request.TeamJoinRequest;
import com.user.model.request.TeamUpdateRequest;
import com.user.partner.service.TeamService;
import com.user.util.common.B;
import com.user.util.common.ErrorCode;
import com.user.util.exception.GlobalException;
import com.user.util.utils.UserUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * @author ice
 * @date 2022/8/22 16:02
 */
@RestController
@RequestMapping("/team")
public class TeamController {

    @Autowired
    private TeamService teamService;

    /**
     * 创建队伍
     * @param teamAddRequest
     * @param request
     * @return
     */
    @PostMapping("addTeam")
    public B<String> addTeam(@RequestBody TeamAddRequest teamAddRequest, HttpServletRequest request) {
        if (teamAddRequest == null) {
            throw new GlobalException(ErrorCode.NULL_ERROR);
        }

        User loginUser = UserUtils.getLoginUser(request);
        Team team = new Team();
        BeanUtils.copyProperties(teamAddRequest,team);
        String userID = teamService.addTeam(team, loginUser);
        if (!StringUtils.hasText(userID)) {
            return B.error(ErrorCode.PARAMS_ERROR,"队伍保存失败");
        }
        return B.ok(userID);
    }

    @PostMapping("/delete")
    public B<Boolean> deleteTeamById(@RequestBody long teamId,HttpServletRequest request) {
        if (teamId <= 0) {
            throw new GlobalException(ErrorCode.NULL_ERROR);
        }
        boolean b = teamService.deleteById(teamId,request);
        if (!b) {
            throw new GlobalException(ErrorCode.SYSTEM_EXCEPTION, "删除失败");
        }
        return B.ok();
    }

    /**
     * 跟新 队伍
     * @param request
     * @return
     */
    @PostMapping("update")
    public B<Boolean> update(@RequestBody TeamUpdateRequest teamUpdateRequest, HttpServletRequest request) {

        teamService.updateTeam(teamUpdateRequest,request);
        return B.ok();
    }

    @GetMapping("/get")
    public B<Team> getTeamById(@RequestParam("id") long id) {
        if (id <= 0) {
            throw new GlobalException(ErrorCode.NULL_ERROR);
        }
        Team team = teamService.getById(id);
        if (team == null) {
            throw new GlobalException(ErrorCode.NULL_ERROR);
        }
        return B.ok(team);
    }

    @GetMapping("/list")
    public B<List<TeamUserVo>> getTeamList(TeamQuery teamQuery,HttpServletRequest request) {
        boolean admin = UserUtils.isAdmin(request);
        List<TeamUserVo> resultPage = teamService.getTeamList(teamQuery,admin);
        return B.ok(resultPage);
    }

    /**
     *  加入队伍
     * @param teamJoinRequest 队伍参数
     * @param request 登录
     * @return b
     */
    @PostMapping ("/join")
    public B<Boolean> addUserTeam(@RequestBody TeamJoinRequest teamJoinRequest, HttpServletRequest request) {
        boolean add = teamService.addUserTeam(teamJoinRequest, request);
        if (add) {
            return B.ok();
        }
        return B.error(ErrorCode.ERROR);
    }

    /**
     * 退出队伍
     * @param teamId
     * @param request
     * @return
     */
    @GetMapping("/quit")
    public B<Boolean> quitTeam(@RequestParam String teamId, HttpServletRequest request) {
        boolean isQuit = teamService.quitTeam(teamId, request);
        if (!isQuit) {
            return B.error(ErrorCode.ERROR,"退出错误,请重试");
        }
        return B.ok();
    }

}
