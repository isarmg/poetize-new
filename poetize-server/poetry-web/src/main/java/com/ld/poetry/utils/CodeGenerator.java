package com.ld.poetry.utils;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.exceptions.MybatisPlusException;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.generator.FastAutoGenerator;
import com.baomidou.mybatisplus.generator.config.rules.NamingStrategy;

import java.util.Scanner;

public class CodeGenerator {

    public static String scanner(String tip) {
        Scanner scanner = new Scanner(System.in);
        StringBuilder help = new StringBuilder();
        help.append("请输入" + tip + "：");
        System.out.println(help.toString());
        if (scanner.hasNext()) {
            String ipt = scanner.next();
            if (StringUtils.isNotBlank(ipt)) {
                return ipt;
            }
        }
        throw new MybatisPlusException("请输入正确的" + tip + "！");
    }

    public static void main(String[] args) {
        String projectPath = System.getProperty("user.dir");
        FastAutoGenerator.create(
                        "jdbc:mysql://ip:port/poetize?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai",
                        "username",
                        "password")
                .globalConfig(builder -> builder
                        .author("sara")
                        .disableOpenDir()
                        .outputDir(projectPath + "\\src\\main\\java"))
                .packageConfig(builder -> builder
                        .parent("com.ld.poetry")
                        .mapper("dao"))
                .strategyConfig(builder -> {
                    builder.addInclude(scanner("表名，多个英文逗号分割").split(","));
                    builder.entityBuilder()
                            .naming(NamingStrategy.underline_to_camel)
                            .columnNaming(NamingStrategy.underline_to_camel)
                            .enableLombok()
                            .enableTableFieldAnnotation()
                            .idType(IdType.AUTO)
                            .logicDeleteColumnName("deleted");
                    builder.controllerBuilder().enableRestStyle();
                    builder.serviceBuilder().formatServiceFileName("%sService");
                    builder.mapperBuilder()
                            .enableBaseResultMap()
                            .enableBaseColumnList();
                })
                .execute();
    }
}
