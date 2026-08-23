package com.ld.poetry.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

@Data
public class UserVO {

    private Integer id;

    @NotBlank(message = "用户名不能为空")
    @Size(max = 32, message = "用户名不能超过 32 个字符")
    private String username;

    @Size(max = 16, message = "手机号不能超过 16 个字符")
    @Pattern(regexp = "^$|\\d{11}", message = "手机号必须为 11 位数字")
    private String phoneNumber;

    @Email(message = "邮箱格式不正确")
    @Size(max = 254, message = "邮箱不能超过 254 个字符")
    private String email;

    @NotBlank(message = "密码不能为空")
    @Size(max = 512, message = "密码凭证过长")
    private String password;

    private Integer gender;

    @Size(max = 256, message = "头像地址不能超过 256 个字符")
    private String avatar;

    @Size(max = 4096, message = "个人简介不能超过 4096 个字符")
    private String introduction;

    private String subscribe;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    private String updateBy;

    private Boolean isBoss = false;

    private String accessToken;

    private String code;
}
