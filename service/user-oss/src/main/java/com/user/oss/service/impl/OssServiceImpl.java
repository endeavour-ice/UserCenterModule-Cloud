package com.user.oss.service.impl;

import cn.hutool.core.date.DateTime;
import cn.hutool.core.util.IdUtil;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.user.model.constant.RedisKey;
import com.user.model.domain.Team;
import com.user.model.domain.User;
import com.user.openfeign.TeamOpenFeign;
import com.user.openfeign.UserOpenFeign;
import com.user.oss.service.OssService;
import com.user.oss.util.ConstantPropertiesUtils;
import com.user.oss.util.ResponseEmail;
import com.user.rabbitmq.config.mq.MqClient;
import com.user.rabbitmq.config.mq.RabbitService;
import com.user.util.common.ErrorCode;
import com.user.util.exception.GlobalException;
import com.user.model.utils.IpUtilSealUp;
import com.user.util.utils.IpUtils;
import com.user.util.utils.RandomUtil;
import com.user.util.utils.TimeUtils;
import com.user.util.utils.UserUtils;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author ice
 * @date 2022/9/17 12:48
 */
@Service
@Slf4j
public class OssServiceImpl implements OssService {
    @Resource
    private RabbitService rabbitService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private UserOpenFeign userOpenFeign;
    @Resource
    private TeamOpenFeign teamOpenFeign;

    @Resource
    private RedissonClient redissonClient;


    /**
     * ?????????????????????
     *
     * @param file ???????????????
     * @param loginUser ???????????????
     * @return ?????????
     */
    @Override
    public String upload(MultipartFile file, User loginUser) {
        RLock lock = redissonClient.getLock(RedisKey.redisFileAvatarLock);
        try {
            if (lock.tryLock(0, 3000, TimeUnit.MILLISECONDS)) {
                if (file == null) {
                    throw new GlobalException(ErrorCode.NULL_ERROR);
                }
                // ???????????????????????????
                String userId = loginUser.getId();
                String redisKey = RedisKey.ossAvatarUserRedisKey + userId;
                String url = getUrl(redisKey, file);
                User user = new User();
                user.setId(userId);
                user.setAvatarUrl(url);
                rabbitService.sendMessage(MqClient.DIRECT_EXCHANGE, MqClient.OSS_KEY, user);
                Integer integer = TimeUtils.getRemainSecondsOneDay(new Date());
                stringRedisTemplate.opsForValue().set(redisKey, new Date().toString(), integer, TimeUnit.SECONDS);
                // ????????????????????????
                rabbitService.sendMessage(MqClient.DIRECT_EXCHANGE, MqClient.REMOVE_REDIS_KEY, RedisKey.redisIndexKey);
                return url;
            }
        } catch (InterruptedException e) {
            throw new GlobalException(ErrorCode.SYSTEM_EXCEPTION, "????????????");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
        return null;
    }

    @Override
    public String upFileByTeam(MultipartFile file, User loginUser, String teamID) {
        RLock lock = redissonClient.getLock(RedisKey.redisFileByTeamAvatarLock);
        try {
            if (lock.tryLock(0, 3000, TimeUnit.MILLISECONDS)) {
                if (file == null || !StringUtils.hasText(teamID)) {
                    throw new GlobalException(ErrorCode.NULL_ERROR);
                }
                String userId = loginUser.getId();
                Team team = teamOpenFeign.getTeamByTeamUser(teamID, userId);
                if (team == null) {
                    throw new GlobalException(ErrorCode.PARAMS_ERROR, "???????????????...");
                }
                String teamUserId = team.getUserId();
                if (!userId.equals(teamUserId)) {
                    throw new GlobalException(ErrorCode.NO_AUTH, "????????????...");
                }
                String redisKey = RedisKey.ossAvatarTeamRedisKey + teamID;
                String url = getUrl(redisKey, file);
                team.setAvatarUrl(url);
                boolean teamByTeam = teamOpenFeign.updateTeamByTeam(team);
                if (!teamByTeam) {
                    throw new GlobalException(ErrorCode.PARAMS_ERROR, "????????????...");
                }
                Integer integer = TimeUtils.getRemainSecondsOneDay(new Date());
                stringRedisTemplate.opsForValue().set(redisKey, new Date().toString(), integer, TimeUnit.SECONDS);
                return url;
            }
        } catch (InterruptedException e) {
            throw new GlobalException(ErrorCode.SYSTEM_EXCEPTION, "????????????");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }

        return null;
    }

    public String getUrl(String redisKey, MultipartFile file) {
        String key = stringRedisTemplate.opsForValue().get(redisKey);
        if (StringUtils.hasText(key)) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR, "????????????...");
        }
        // Endpoint?????????1???????????????????????????Region???????????????????????????
        String endpoint = ConstantPropertiesUtils.END_POINT;
        // ???????????????AccessKey????????????API???????????????????????????????????????????????????????????????RAM????????????API?????????????????????????????????RAM???????????????RAM?????????
        String accessKeyId = ConstantPropertiesUtils.ACCESS_KEY_ID;
        String accessKeySecret = ConstantPropertiesUtils.ACCESS_KEY_SECRET;
        // ??????Bucket???????????????examplebucket???
        String bucketName = ConstantPropertiesUtils.BUCKET_NAME;
        // ??????Object?????????????????????exampledir/exampleobject.txt???Object???????????????????????????Bucket?????????
        // ??????Object??????????????????????????????????????????Bucket???????????????exampledir/exampleobject.txt???
        // ??????????????????????????????
        String originalFilename = IdUtil.simpleUUID() + file.getOriginalFilename();
        String objectName = "user/" + new DateTime().toString("yyyy/MM/dd") + "/" + originalFilename;

        // ??????OSSClient?????????
        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);

        try {
            InputStream inputStream = file.getInputStream();
            // ??????PutObject?????????
            ossClient.putObject(bucketName, objectName, inputStream);
            return "https://" + bucketName + "." + endpoint + "/" + objectName;
        } catch (Exception oe) {
            log.error(oe.getMessage());
            throw new GlobalException(ErrorCode.PARAMS_ERROR, "????????????");
        } finally {
            if (ossClient != null) {
                ossClient.shutdown();
            }
        }
    }

    /**
     * ????????????
     *
     * @param responseEmail responseEmail
     * @param request ????????????
     * @return true
     */
    @Override
    public boolean sendForgetEMail(ResponseEmail responseEmail, HttpServletRequest request) {
        RLock lock = redissonClient.getLock(RedisKey.redisFileByForgetLock);
        try {
            if (lock.tryLock(0, 3000, TimeUnit.MILLISECONDS)) {
                // ????????????ip
                ipEmailUtil(request);
                if (responseEmail == null) {
                    throw new GlobalException(ErrorCode.NULL_ERROR, "???????????????");
                }
                String email = responseEmail.getEmail();
                String userAccount = responseEmail.getUserAccount();
                if (!StringUtils.hasText(email)) {
                    throw new GlobalException(ErrorCode.NULL_ERROR, "???????????????");
                }
                if (!StringUtils.hasText(userAccount)) {
                    throw new GlobalException(ErrorCode.NULL_ERROR, "???????????????");
                }
                String pattern = "\\w[-\\w.+]*@([A-Za-z0-9][-A-Za-z0-9]+\\.)+[A-Za-z]{2,14}";

                Matcher matcher = Pattern.compile(pattern).matcher(email);
                if (!matcher.matches()) {
                    throw new GlobalException(ErrorCode.PARAMS_ERROR, "?????????????????????");
                }
                User user = userOpenFeign.forgetUserEmail(email);

                if (user == null) {
                    throw new GlobalException(ErrorCode.NULL_ERROR, "????????????????????????");
                }
                String userUserAccount = user.getUserAccount();
                if (!userAccount.equals(userUserAccount)) {
                    throw new GlobalException(ErrorCode.NULL_ERROR, "?????????????????????????????????");
                }

                String code = RandomUtil.getRandomFour();
                String[] split = email.split("@");
                String name = split[0];
                boolean sendQQEmail = sendQQEmail(email, code, name);
                if (!sendQQEmail) {
                    throw new GlobalException(ErrorCode.PARAMS_ERROR, "?????????????????????");
                }
                String redisKey = RedisKey.redisForgetCode + email;
                try {
                    stringRedisTemplate.opsForValue().set(redisKey, code, 60, TimeUnit.SECONDS);
                } catch (Exception e) {
                    return false;
                }
            }
        } catch (InterruptedException e) {
            throw new GlobalException(ErrorCode.SYSTEM_EXCEPTION, "????????????");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
        return false;
    }

    /**
     * ??????????????????
     *
     * @param responseEmail ???????????????
     * @return ??????Boolean
     */
    @Override
    public boolean sendEMail(ResponseEmail responseEmail, HttpServletRequest request) {
        RLock lock = redissonClient.getLock(RedisKey.redisFileByRegisterLock);
        try {
            if (lock.tryLock(0, 3000, TimeUnit.MILLISECONDS)) {
                // ????????????ip
                ipEmailUtil(request);

                if (responseEmail == null) {
                    throw new GlobalException(ErrorCode.NULL_ERROR, "???????????????");
                }
                String email = getEmail(responseEmail);
                if (userOpenFeign.seeUserEmail(email)) {
                    throw new GlobalException(ErrorCode.PARAMS_ERROR, "??????????????????");
                }
                String code = getCode(email);
                String redisKey = RedisKey.redisRegisterCode + email;
                try {
                    stringRedisTemplate.opsForValue().set(redisKey, code, 60, TimeUnit.SECONDS);
                } catch (Exception e) {
                    return false;
                }
            }
        } catch (InterruptedException e) {
            throw new GlobalException(ErrorCode.SYSTEM_EXCEPTION, "????????????");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
        return true;
    }

    /**
     * ?????????????????????
     *
     * @param responseEmail responseEmail
     * @param request s
     * @return s
     */
    @Override
    public boolean sendBinDingEMail(ResponseEmail responseEmail, HttpServletRequest request) {
        RLock lock = redissonClient.getLock(RedisKey.redisFileByBingDingLock);
        try {
            if (lock.tryLock(0, 3000, TimeUnit.MILLISECONDS)) {
                // ????????????ip

                ipEmailUtil(request);
                User user = UserUtils.getLoginUser(request);
                String email = getEmail(responseEmail);
                String userEmail = user.getEmail();
                if (StringUtils.hasText(userEmail)) {
                    if (!userEmail.equals(email)) {
                        throw new GlobalException(ErrorCode.PARAMS_ERROR, "");
                    }
                }
                if (userOpenFeign.seeUserEmail(email)) {
                    throw new GlobalException(ErrorCode.PARAMS_ERROR, "?????????????????????");
                }

                String code = getCode(email);
                String redisKey = RedisKey.redisFileByBingDingKey + email;
                try {
                    stringRedisTemplate.opsForValue().set(redisKey, code, 60, TimeUnit.SECONDS);
                } catch (Exception e) {
                    return false;
                }
            }
        } catch (InterruptedException e) {
            throw new GlobalException(ErrorCode.SYSTEM_EXCEPTION, "????????????");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
        return true;
    }

    private String getCode(String email) {
        String code = RandomUtil.getRandomSix();
        String[] split = email.split("@");
        String name = split[0];
        boolean sendQQEmail = sendQQEmail(email, code, name);
        if (!sendQQEmail) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR, "?????????????????????");
        }
        return code;
    }

    private String getEmail(ResponseEmail responseEmail) {
        String email = responseEmail.getEmail();
        if (!StringUtils.hasText(email)) {
            throw new GlobalException(ErrorCode.NULL_ERROR, "???????????????");
        }
        String pattern = "\\w[-\\w.+]*@([A-Za-z0-9][-A-Za-z0-9]+\\.)+[A-Za-z]{2,14}";

        Matcher matcher = Pattern.compile(pattern).matcher(email);
        if (!matcher.matches()) {
            throw new GlobalException(ErrorCode.PARAMS_ERROR, "?????????????????????");
        }
        return email;
    }

    /**
     * ????????????(??????????????????????????????????????????????????????????????????)
     *
     * @param receives ??????????????????
     * @param code     ?????????
     * @param name     ??????????????????
     * @return ????????????
     */
    public boolean sendQQEmail(String receives, String code, String name) {
        String from_email = ConstantPropertiesUtils.EMAIL;
        String pwd = ConstantPropertiesUtils.EMAILPASSWORD;
        Properties props = new Properties();
        props.setProperty("mail.transport.protocol", "smtp");     //??????smpt?????????????????????
        props.setProperty("mail.smtp.host", "smtp.qq.com");       //????????????
        props.setProperty("mail.smtp.auth", "true");      //????????????

        Session session = Session.getInstance(props);     //??????????????????????????????????????????????????????

        try {

            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(from_email));     //???????????????
            message.setRecipient(MimeMessage.RecipientType.TO, new InternetAddress(receives, "??????", "utf-8"));      //???????????????
            message.setSubject("?????????", "utf-8");      //????????????
            message.setSentDate(new Date());

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy???MM???dd??? HH:mm:ss");
            // ??????
            String str = "<!DOCTYPE html><html><head><meta charset='UTF-8'></head><body><p style='font-size: 20px;font-weight:bold;'>????????????" + name + "????????????</p>"
                    + "<p style='text-indent:2em; font-size: 20px;'>????????????????????????????????????????????????????????? "
                    + "<span style='font-size:30px;font-weight:bold;color:red'>" + code + "</span>???1???????????????????????????????????????</p>"
                    + "<p style='text-align:right; padding-right: 20px;'"
                    + "<a href='http://www.hyycinfo.com' style='font-size: 18px'></a></p>"
                    + "<span style='font-size: 18px; float:right; margin-right: 60px;'>" + sdf.format(new Date()) + "</span></body></html>";

            Multipart mul = new MimeMultipart();  //????????????MimeMultipart?????????????????????BodyPart??????
            BodyPart mdp = new MimeBodyPart();  //?????????????????????????????????BodyPart??????
            mdp.setContent(str, "text/html;charset=utf-8");
            mul.addBodyPart(mdp);  //????????????????????????BodyPart?????????MimeMultipart?????????
            message.setContent(mul); //???mul??????????????????


            message.saveChanges();

            //????????????????????????
            Transport transport = session.getTransport("smtp");

            //???????????????????????????  465????????? SSL??????
            transport.connect("smtp.qq.com", 587, from_email, pwd);

            //????????????
            transport.sendMessage(message, message.getAllRecipients());

            //??????????????????
            transport.close();

        } catch (UnsupportedEncodingException | MessagingException e) {
            log.error(e.getMessage());
            return false;
        }

        return true;
    }

    private void ipEmailUtil(HttpServletRequest request) {
        String ipAddress = IpUtils.getIpAddress(request);

        String num = stringRedisTemplate.opsForValue().get(ipAddress);
        if (StringUtils.hasText(num)) {
            int max = Integer.parseInt(num);
            // ?????????????????????
            if (max >= 20) {
                IpUtilSealUp.addIpList(ipAddress);
                throw new GlobalException(ErrorCode.PARAMS_ERROR);
            }
            stringRedisTemplate.opsForValue().increment(ipAddress);
        } else {
            stringRedisTemplate.opsForValue().set(ipAddress, "1", TimeUtils.getRemainSecondsOneDay(new Date()),
                    TimeUnit.SECONDS);
        }
    }

}
