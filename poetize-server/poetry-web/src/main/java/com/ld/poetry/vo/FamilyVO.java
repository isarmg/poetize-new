package com.ld.poetry.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

@Data
public class FamilyVO {

    private Integer id;

    private Integer userId;

    /**
     * 背景封面
     */
    @NotBlank(message = "背景封面不能为空")
    @Size(max = 256, message = "背景封面地址不能超过 256 个字符")
    private String bgCover;

    /**
     * 男生头像
     */
    @NotBlank(message = "男生头像不能为空")
    @Size(max = 256, message = "男生头像地址不能超过 256 个字符")
    private String manCover;

    /**
     * 女生头像
     */
    @NotBlank(message = "女生头像不能为空")
    @Size(max = 256, message = "女生头像地址不能超过 256 个字符")
    private String womanCover;

    /**
     * 男生昵称
     */
    @NotBlank(message = "男生昵称不能为空")
    @Size(max = 32, message = "男生昵称不能超过 32 个字符")
    private String manName;

    /**
     * 女生昵称
     */
    @NotBlank(message = "女生昵称不能为空")
    @Size(max = 32, message = "女生昵称不能超过 32 个字符")
    private String womanName;

    /**
     * 计时
     */
    @NotBlank(message = "计时不能为空")
    @Size(max = 32, message = "计时时间不能超过 32 个字符")
    private String timing;

    /**
     * 倒计时标题
     */
    @Size(max = 32, message = "倒计时标题不能超过 32 个字符")
    private String countdownTitle;

    /**
     * 是否启用[0:否，1:是]
     */
    private Boolean status;

    /**
     * 倒计时时间
     */
    @Size(max = 32, message = "倒计时时间不能超过 32 个字符")
    private String countdownTime;

    /**
     * 额外信息
     */
    @Size(max = 1024, message = "额外信息不能超过 1024 个字符")
    private String familyInfo;

    /**
     * 点赞数
     */
    private Integer likeCount;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}
