package com.user.model.request;

import lombok.Data;

import java.io.Serializable;

/**
 * @Author ice
 * @Date 2022/10/3 12:17
 * @PackageName:com.user.model.request
 * @ClassName: UpdateUserRequest
 * @Description: 修改用户
 * @Version 1.0
 */
@Data
public class UpdateUserRequest implements Serializable {
    private static final long serialVersionUID = -4689454932816955493L;
    private String id;
    private String username;
    private String gender;
    private String tags;
    private String profile;
    private String email;
    private String tel;
}
