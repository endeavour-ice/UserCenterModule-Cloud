package com.user.util.utils;

import org.springframework.util.DigestUtils;

import java.util.HashMap;

/**
 * @author ice
 * @date 2022/6/14 15:41
 */

public class MD5 {
    private static final  String SALT = "ice";
    private static final  String TEAMS = "jb";


    public static String getMD5(String password) {
        // 加密密码
        return DigestUtils.md5DigestAsHex((SALT + password).getBytes());
    }

    public static String getTeamMD5(String teamPassword) {
        // 加密密码
        return DigestUtils.md5DigestAsHex((TEAMS + teamPassword).getBytes());
    }

    public static void main(String[] args) {

        String md5 = getMD5("123456");
        System.out.println(md5);
        HashMap<String, Object> hashMap = new HashMap<>();
        int hashCode = "asdassdfaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaasadfdsagfdsafsdfsdafsadfsdafsdfsdfsdfsdfsdafsadfsadfsdafsdafsdafdsfsdajklfhgsadkjfhkjsadhfksadhkjfaksdhfkjsahfkjdshfksdhfkjashfjklsdahfksadhkfhasdkhfkhfkljdakljfhdksahfkjsdhajkfashdkjfashfdkjasdhfjkashfkashfkasklfhjikasfhjklash".hashCode();
        System.out.println(hashCode);
        int s = 123;
    }
}
