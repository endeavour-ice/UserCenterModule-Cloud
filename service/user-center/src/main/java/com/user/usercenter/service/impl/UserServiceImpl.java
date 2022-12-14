package com.user.usercenter.service.impl;


import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.user.model.constant.RedisKey;
import com.user.model.constant.UserStatus;
import com.user.model.domain.User;
import com.user.model.request.UserRegisterRequest;
import com.user.model.request.UserSearchTagAndTxtRequest;
import com.user.rabbitmq.config.mq.MqClient;
import com.user.rabbitmq.config.mq.RabbitService;
import com.user.usercenter.mapper.UserMapper;
import com.user.usercenter.service.IUserService;
import com.user.util.common.ErrorCode;
import com.user.util.exception.GlobalException;
import com.user.util.utils.*;
import javafx.util.Pair;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.user.model.constant.UserConstant.ADMIN_ROLE;
import static com.user.model.constant.UserConstant.USER_LOGIN_STATE;

/**
 * <p>
 * 用户表 服务实现类
 * </p>
 *
 * @author ice
 * @since 2022-06-14
 */
@Service
@Log4j2
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {


    @Autowired
    private StringRedisTemplate redisTemplate;

    @Resource
    private RabbitService rabbitService;

    @Override
    public Long userRegister(UserRegisterRequest userRegister) {
        String userAccount = userRegister.getUserAccount();
        String password = userRegister.getPassword();
        String checkPassword = userRegister.getCheckPassword();
        String planetCode = userRegister.getPlanetCode();
        String code = userRegister.getCode();
        String email = userRegister.getEmail();


        if (!StringUtils.hasText(planetCode)) {
            planetCode = RandomUtil.randomInt(10, 10000) + "";
        }
        boolean hasEmpty = StrUtil.hasEmpty(userAccount, password, checkPassword, planetCode);
        if (hasEmpty) {
            throw new GlobalException(ErrorCode.NULL_ERROR);
        }
        // 1. 校验
        if (StrUtil.hasEmpty(userAccount, password, checkPassword, planetCode)) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (userAccount.length() < 3) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR, "用户名过短");
        }

        if (password.length() < 6 || checkPassword.length() < 6) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR, "密码过短");
        }
        if (planetCode.length() > 5) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR, "编号过长");
        }
        // 校验账户不能包含特殊字符
        String pattern = "^([\\u4e00-\\u9fa5]+|[a-zA-Z0-9]+)$";
        Matcher matcher = Pattern.compile(pattern).matcher(userAccount);
        if (!matcher.find()) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR, "账号特殊符号");
        }
        // 判断密码和和用户名是否相同
        if (password.equals(userAccount)) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR, "账号密码相同");
        }
        if (!password.equals(checkPassword)) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR, "确认密码错误");
        }

        if (!StringUtils.hasText(email)) {
            throw new GlobalException(ErrorCode.NULL_ERROR, "邮箱为空");
        }
        pattern = "\\w[-\\w.+]*@([A-Za-z0-9][-A-Za-z0-9]+\\.)+[A-Za-z]{2,14}";
        matcher = Pattern.compile(pattern).matcher(email);
        if (!matcher.matches()) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR, "请输入正确邮箱");
        }
        if (!StringUtils.hasText(code)) {
            throw new GlobalException(ErrorCode.NULL_ERROR, "验证码为空");
        }
        String redisCode = redisTemplate.opsForValue().get(RedisKey.redisRegisterCode + email);
        if (!StringUtils.hasText(redisCode) || !code.equals(redisCode)) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR, "验证码错误，请重试");
        }
        // 判断用户是否重复
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.eq("user_account", userAccount);
        Long aLong = baseMapper.selectCount(wrapper);
        if (aLong > 0) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR, "注册用户重复");
        }
        // 判断用户是否重复
        wrapper = new QueryWrapper<>();
        wrapper.eq("planet_code", planetCode);
        Long a = baseMapper.selectCount(wrapper);
        if (a > 0) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR, "注册用户编号重复");
        }
        wrapper = new QueryWrapper<>();
        wrapper.eq("email", email);
        Long count = baseMapper.selectCount(wrapper);
        if (count > 0) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR, "注册邮箱重复");
        }
        // 加密密码
        String passwordMD5 = MD5.getMD5(password);
        User user = new User();
        user.setUserAccount(userAccount);
        user.setPassword(passwordMD5);
        user.setPlanetCode(planetCode);
        user.setAvatarUrl("https://wpimg.wallstcn.com/f778738c-e4f8-4870-b634-56703b4acafe.gif?imageView2/1/w/80/h/80");
        user.setUsername(userAccount);
        user.setEmail(email);
        boolean save = this.save(user);
        if (!save) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR, "注册用户失败");
        }
        rabbitService.sendMessage(MqClient.DIRECT_EXCHANGE, MqClient.REMOVE_REDIS_KEY, RedisKey.redisIndexKey);
        return Long.parseLong(user.getId());
    }

    @Override
    public User userLogin(String userAccount, String password, HttpServletRequest request) {
        // 1. 校验
        if (userAccount.length() < 3) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR, "账号错误");
        }
        if (password.length() < 6) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR, "密码错误");
        }

        // 校验账户不能包含特殊字符
        String pattern = "^([\\u4e00-\\u9fa5]+|[a-zA-Z0-9]+)$";
        Matcher matcher = Pattern.compile(pattern).matcher(userAccount);
        if (!matcher.find()) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR, "账户不能包含特殊字符");
        }
        String passwordMD5 = MD5.getMD5(password);
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.eq("user_account", userAccount);
        wrapper.eq("password", passwordMD5);
        User user = baseMapper.selectOne(wrapper);

        if (user == null) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR, "账号密码错误");
        }

        // 用户脱敏
        if (user.getUserStatus().equals(UserStatus.LOCKING)) {
            throw new GlobalException(ErrorCode.NO_AUTH, "该用户以锁定...");
        }
        User cleanUser = getSafetyUser(user);
        // 记录用户的登录态
        HttpSession session = request.getSession();
        String token = JwtUtils.getJwtToken(cleanUser);
        session.setAttribute(USER_LOGIN_STATE, token);
        return cleanUser;
    }

    /**
     * 用户脱敏
     */
    @Override
    public User getSafetyUser(User user) {
        if (user == null) {
            return null;
        }
        User cleanUser = new User();
        cleanUser.setId(user.getId());
        cleanUser.setUsername(user.getUsername());
        cleanUser.setUserAccount(user.getUserAccount());
        cleanUser.setAvatarUrl(user.getAvatarUrl());
        cleanUser.setGender(user.getGender());
        cleanUser.setTel(user.getTel());
        cleanUser.setEmail(user.getEmail());
        cleanUser.setUserStatus(user.getUserStatus());
        cleanUser.setCreateTime(user.getCreateTime());
        cleanUser.setRole(user.getRole());
        cleanUser.setPlanetCode(user.getPlanetCode());
        cleanUser.setTags(user.getTags());
        cleanUser.setProfile(user.getProfile());
        return cleanUser;
    }

    @Override
    public boolean isAdmin(HttpServletRequest request) {
        // 仅管理员查询
        User user = (User) request.getSession().getAttribute(USER_LOGIN_STATE);

        return user != null && Objects.equals(user.getRole(), ADMIN_ROLE);
    }

    /**
     * 用户注销
     *
     * @param request 1
     */
    @Override
    public void userLogout(HttpServletRequest request) {
        request.getSession().removeAttribute(USER_LOGIN_STATE);
    }

    /**
     * 修改用户
     *
     * @param user
     * @return
     */
    @Override
    public Integer updateUser(User user, HttpServletRequest request) {
        if (user == null) {
            throw new GlobalException(ErrorCode.NULL_ERROR);
        }
        boolean admin = isAdmin(request);
        if (!admin) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR, "你不是管理员");
        }
        int update = baseMapper.updateById(user);
        if (update <= 0) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR, "修改失败");
        }
        return update;
    }


    /**
     * ===============================================================
     * 根据标签搜索用户
     *
     * @return 返回用户列表
     */
    @Override
    public List<User> searchUserTag(UserSearchTagAndTxtRequest userSearchTagAndTxtRequest) {
        if (userSearchTagAndTxtRequest == null) {
            throw new GlobalException(ErrorCode.NULL_ERROR, "请求数据为空");
        }

        List<String> tagNameList = userSearchTagAndTxtRequest.getTagNameList();
        String searchTxt = userSearchTagAndTxtRequest.getSearchTxt();
        if (!StringUtils.hasText(searchTxt) && CollectionUtils.isEmpty(tagNameList)) {
            throw new GlobalException(ErrorCode.NULL_ERROR, "请求数据为空");
        }
        // sql 语句查询
//        QueryWrapper<User> wrapper = new QueryWrapper<>();
//        // 拼接and 查询
//        for (String tagName : tagNameList) {
//            wrapper = wrapper.like("tags", tagName);
//        }
//        List<User> userList = baseMapper.selectList(wrapper);
        // 内存查询
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        if (StringUtils.hasText(searchTxt)) {
            wrapper.and(wq -> wq.like("username", searchTxt).or().like("user_account", searchTxt)
                    .or().like("gender", searchTxt).or().like("tel", searchTxt).or().like("email", searchTxt).
                    like("profile", searchTxt));
        }
        List<User> userList = baseMapper.selectList(wrapper);
        if (userList.size() <= 0) {
            return new ArrayList<>();
        }

        if (!CollectionUtils.isEmpty(tagNameList)) {
            Gson gson = new Gson();
            return userList.stream().filter(user -> {
                String tagStr = user.getTags();
                // 将json 数据解析成 Set
                Set<String> tempTagNameSet = gson.fromJson(tagStr, new TypeToken<Set<String>>() {
                }.getType());
                tempTagNameSet = Optional.ofNullable(tempTagNameSet).orElse(new HashSet<>());
                for (String tagName : tagNameList) {
                    if (tempTagNameSet.contains(tagName)) {
                        return true;
                    }
                }
                return false;
            }).map(this::getSafetyUser).collect(Collectors.toList());
        }

        return userList;
    }


    @Override
    public Map<String, Object> selectPageIndexList(long current, long size) {
        if (size > 300) {
            throw new GlobalException(ErrorCode.SYSTEM_EXCEPTION, "请求参数有误");
        }
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.select("avatar_url", "user_account", "id", "tags", "profile");
        Page<User> commentPage = baseMapper.selectPage(new Page<>(current, size), wrapper);
        Map<String, Object> map = new HashMap<>();
        List<User> userList = commentPage.getRecords();
        map.put("items", userList);
        map.put("current", commentPage.getCurrent());
        map.put("pages", commentPage.getPages());
        map.put("size", commentPage.getSize());
        map.put("total", commentPage.getTotal());
        map.put("hasNext", commentPage.hasNext());
        map.put("hasPrevious", commentPage.hasPrevious());
        return map;
    }

    /**
     * 根据用户修改资料
     */
    @Override
    public long getUserByUpdateID(User loginUser, User updateUser) {
        String userId = updateUser.getId();
        String username = updateUser.getUsername();
        String gender = updateUser.getGender();
        String tel = updateUser.getTel();
        String email = updateUser.getEmail();
        String profile = updateUser.getProfile();
        String tags = updateUser.getTags();
        if (StringUtils.hasText(username)) {
            String regEx = "\\pP|\\pS|\\s+";
            username = Pattern.compile(regEx).matcher(username).replaceAll("").trim();
        }
        if (!StringUtils.hasText(userId) || Long.parseLong(userId) <= 0) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR);
        }

        if (!StringUtils.hasText(username) && !StringUtils.hasText(tel) &&
                !StringUtils.hasText(email) && !StringUtils.hasText(tags)
                && !StringUtils.hasText(gender) && !StringUtils.hasText(profile)) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR);
        }
        if (StringUtils.hasText(profile)) {
            if (profile.length() >= 200) {
                throw new GlobalException(ErrorCode.PARAMS_ERROR, "描述过长");
            }
        }
        if (StringUtils.hasText(gender) && !"男".equals(gender) && !"女".equals(gender)) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR);
        }
        if (!isAdmin(loginUser) && !userId.equals(loginUser.getId())) {
            throw new GlobalException(ErrorCode.NO_AUTH);
        }
        User oldUser = baseMapper.selectById(userId);
        if (oldUser == null) {
            throw new GlobalException(ErrorCode.NULL_ERROR);
        }
        if (StringUtils.hasText(tel)) {
            String pattern = "0?(13|14|15|18|17)[0-9]{9}";
            boolean matches = Pattern.compile(pattern).matcher(tel).matches();
            if (!matches) {
                throw new GlobalException(ErrorCode.PARAMS_ERROR,"手机号格式错误");
            }
        }

        if (StringUtils.hasText(tags)) {
            String oldUserTags = oldUser.getTags();
            if (StringUtils.hasText(oldUserTags) && oldUserTags.equals(tags)) {
                throw new GlobalException(ErrorCode.NULL_ERROR, "重复提交...");
            }
            return this.TagsUtil(userId, updateUser);
        }
        int update = baseMapper.updateById(updateUser);
        if (update > 0) {
            rabbitService.sendMessage(MqClient.DIRECT_EXCHANGE, MqClient.REMOVE_REDIS_KEY, RedisKey.redisIndexKey);
        } else {
            throw new GlobalException(ErrorCode.PARAMS_ERROR, "修改失败");
        }
        return update;

    }

    public boolean isAdmin(User user) {

        return user != null && Objects.equals(user.getRole(), ADMIN_ROLE);
    }

    @Override
    public List<User> friendUserName(String userID, String friendUserName) {
        if (!StringUtils.hasText(friendUserName)) {
            throw new GlobalException(ErrorCode.NULL_ERROR);
        }
        QueryWrapper<User> userQueryWrapper = new QueryWrapper<>();
        userQueryWrapper.like("user_account", friendUserName);
        List<User> userList = baseMapper.selectList(userQueryWrapper);
        if (userList.size() == 0) {
            throw new GlobalException(ErrorCode.NULL_ERROR, "查无此人");
        }
        userList = userList.stream().filter(user -> !userID.equals(user.getId())).collect(Collectors.toList());
        userList.forEach(this::getSafetyUser);
        return userList;

    }


    public int TagsUtil(String userId, User updateUser) {
        String tagKey = RedisKey.tagRedisKey + userId;
        String tagNum = redisTemplate.opsForValue().get(tagKey);
        if (!StringUtils.hasText(tagNum)) {
            int i = baseMapper.updateById(updateUser);
            if (i <= 0) {
                throw new GlobalException(ErrorCode.SYSTEM_EXCEPTION, "保存失败...");
            }
            redisTemplate.opsForValue().set(tagKey, "1",
                    TimeUtils.getRemainSecondsOneDay(new Date()), TimeUnit.SECONDS);
            return i;
        }
        int parseInt;
        try {
            parseInt = Integer.parseInt(tagNum);
        } catch (Exception e) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR);
        }
        if (parseInt > 5) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR, "今天修改次数以上限...");
        }
        int i = baseMapper.updateById(updateUser);
        if (i <= 0) {
            throw new GlobalException(ErrorCode.SYSTEM_EXCEPTION, "保存失败...");
        }
        redisTemplate.opsForValue().increment(tagKey);
        return i;
    }

    @Override
    public Map<String, Object> searchUser(HttpServletRequest request, String username, Long current, Long size) {


        boolean admin = this.isAdmin(request);
        if (!admin) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR, "你不是管理员");
        }
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        // 如果name有值
        if (StringUtils.hasText(username)) {
            wrapper.like("username", username);
        }
        if (current == null || size == null) {

            current = 1L;
            size = 30L;
        }
        if (size > 30L) {
            throw new GlobalException(ErrorCode.SYSTEM_EXCEPTION, "请求数据有误");
        }
        Page<User> page = new Page<>(current, size);
        Page<User> userPage = baseMapper.selectPage(page, wrapper);
        userPage.getRecords().forEach(this::getSafetyUser);
        Map<String, Object> map = new HashMap<>();
        map.put("records", userPage.getRecords());
        map.put("current", userPage.getCurrent());
        map.put("total", userPage.getTotal());
        return map;
    }

    /**
     * 搜索用户的标签
     *
     * @param tag     标签
     * @param request 登录的请求
     * @return 返回标签
     */
    @Override
    public List<User> searchUserTag(String tag, HttpServletRequest request) {
        if (!StringUtils.hasText(tag)) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR);
        }
        UserUtils.getLoginUser(request);
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.like("tags", tag);
        Page<User> commentPage = baseMapper.selectPage(new Page<>(1, 200), wrapper);
        List<User> list = commentPage.getRecords();
        list.parallelStream().forEach(this::getSafetyUser);
        return list;
    }

    /**
     * 通过编辑距离算法 推荐用户
     *
     * @param num     推荐的数量
     * @param request 登录
     * @return 返回
     */
    @Override
    public List<User> matchUsers(long num, HttpServletRequest request) {
        User loginUser = UserUtils.getLoginUser(request);
        String tags = loginUser.getTags();
        Gson gson = new Gson();
        List<String> tagList = gson.fromJson(tags, new TypeToken<List<String>>() {
        }.getType());
//        SortedMap<Integer, User> indexDistanceMap = new TreeMap<>();
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.select("id", "tags");
        wrapper.isNotNull("tags");
        List<User> userList = this.list(wrapper);
        List<Pair<Integer, String>> pairs = new ArrayList<>();
        for (User user : userList) {
            String userTags = user.getTags();
            if (!StringUtils.hasText(userTags) || user.getId().equals(loginUser.getId())) {
                continue;
            }
            List<String> tagUserList = gson.fromJson(userTags, new TypeToken<List<String>>() {
            }.getType());
            int distance = AlgorithmUtils.minDistance(tagList, tagUserList);
            int size = pairs.size();
            if (size - 1 >= num) {
                pairs.sort(Comparator.comparingInt(Pair::getKey));
                Pair<Integer, String> pair = pairs.get(size - 1);
                Integer key = pair.getKey();
                if (distance >= key) {
                    continue;
                }
                pairs.set(size - 1, new Pair<>(distance, user.getId()));

            } else {
                pairs.add(new Pair<>(distance, user.getId()));
            }
        }
        List<User> findUserList = new ArrayList<>();
        if (pairs.size() > 0) {
            List<String> userIds = pairs.stream().map(Pair::getValue).collect(Collectors.toList());
            List<User> users = this.listByIds(userIds);
            if (users == null || users.size() <= 0) {
                return findUserList;
            }
            Map<String, List<User>> userListByUserIdMap = users.stream().map(this::getSafetyUser).collect(Collectors.groupingBy(User::getId));

            for (String userId : userIds) {
                findUserList.add(userListByUserIdMap.get(userId).get(0));
            }
        }
        return findUserList;
//        return indexDistanceMap.keySet().parallelStream().map(indexDistanceMap::get).limit(num).collect(Collectors.toList());
    }

    @Override
    public boolean userForget(UserRegisterRequest registerRequest) {
        if (registerRequest == null) {
            throw new GlobalException(ErrorCode.NULL_ERROR, "账号为空");
        }
        String userAccount = registerRequest.getUserAccount();
        String email = registerRequest.getEmail();
        String code = registerRequest.getCode();
        String password = registerRequest.getPassword();
        String checkPassword = registerRequest.getCheckPassword();
        if (!StringUtils.hasText(userAccount)) {
            throw new GlobalException(ErrorCode.NULL_ERROR, "账号为空");
        }
        if (!StringUtils.hasText(email)) {
            throw new GlobalException(ErrorCode.NULL_ERROR, "邮箱为空");
        }
        if (!StringUtils.hasText(code)) {
            throw new GlobalException(ErrorCode.NULL_ERROR, "验证码为空");
        }
        if (!StringUtils.hasText(password)) {
            throw new GlobalException(ErrorCode.NULL_ERROR, "密码为空");
        }
        if (!StringUtils.hasText(checkPassword)) {
            throw new GlobalException(ErrorCode.NULL_ERROR, "确认密码为空");
        }
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.eq("email", email);
        User user = this.getOne(wrapper);
        if (user == null) {
            throw new GlobalException(ErrorCode.NULL_ERROR, "该邮箱没有注册过");
        }
        String userUserAccount = user.getUserAccount();
        if (!userAccount.equals(userUserAccount)) {
            throw new GlobalException(ErrorCode.NULL_ERROR, "请输入该邮箱绑定的账号");
        }
        String redisCode = redisTemplate.opsForValue().get(RedisKey.redisForgetCode + email);
        if (!StringUtils.hasText(redisCode)) {
            throw new GlobalException(ErrorCode.NULL_ERROR, "验证码已过期请重试");
        }
        if (!code.equals(redisCode)) {
            throw new GlobalException(ErrorCode.NULL_ERROR, "验证码错误");
        }
        String md5 = MD5.getMD5(password);
        user.setPassword(md5);
        boolean u = this.updateById(user);
        if (!u) {
            throw new GlobalException(ErrorCode.SYSTEM_EXCEPTION, "修改错误请刷新重试");
        }
        return u;
    }
}
