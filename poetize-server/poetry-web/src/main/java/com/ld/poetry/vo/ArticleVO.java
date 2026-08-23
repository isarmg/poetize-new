package com.ld.poetry.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.ld.poetry.entity.Label;
import com.ld.poetry.entity.Sort;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

@Data
public class ArticleVO {

    private Integer id;

    private Integer userId;

    //查询为空时，随机选择
    @Size(max = 256, message = "文章封面地址不能超过 256 个字符")
    private String articleCover;

    @NotBlank(message = "文章标题不能为空")
    @Size(max = 32, message = "文章标题不能超过 32 个字符")
    private String articleTitle;

    @NotBlank(message = "文章内容不能为空")
    private String articleContent;

    private Integer viewCount;

    private Integer likeCount;

    private Boolean commentStatus;

    private Boolean recommendStatus;

    @Size(max = 1024, message = "视频地址不能超过 1024 个字符")
    private String videoUrl;

    @Size(max = 128, message = "文章密码不能超过 128 个字符")
    private String password;

    @Size(max = 128, message = "密码提示不能超过 128 个字符")
    private String tips;

    private Boolean viewStatus;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    private String updateBy;

    @NotNull(message = "文章分类不能为空")
    private Integer sortId;

    @NotNull(message = "文章标签不能为空")
    private Integer labelId;

    // 需要查询封装
    private Integer commentCount;
    private String username;
    private Sort sort;
    private Label label;
    private Boolean hasVideo = false;
}
