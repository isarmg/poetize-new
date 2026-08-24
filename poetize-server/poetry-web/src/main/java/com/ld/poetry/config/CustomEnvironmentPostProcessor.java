package com.ld.poetry.config;

import com.ld.poetry.handle.PoetryRuntimeException;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.io.IOException;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;

@Order(Ordered.LOWEST_PRECEDENCE)
public class CustomEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String SOURCE_NAME = "sys_config";

    private static final String SOURCE_SQL = "select config_key, config_value from sys_config order by id";

    private static final String DEFAULT_INIT_SCRIPT = "file:/home/poetry.sql";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        try {
            Map<String, Object> map = new HashMap<>();

            String username = environment.getProperty("spring.datasource.username");
            String password = environment.getProperty("spring.datasource.password");
            String url = environment.getProperty("spring.datasource.url");
            String driver = environment.getProperty("spring.datasource.driver-class-name");
            String initScript = environment.getProperty("poetry.database.init-script", DEFAULT_INIT_SCRIPT);
            Class.forName(driver);
            try (Connection connection = DriverManager.getConnection(url, username, password)) {
                //初始化数据库
                initDb(connection, initScript);
                //加载配置文件
                try (Statement statement = connection.createStatement()) {
                    try (ResultSet resultSet = statement.executeQuery(SOURCE_SQL)) {
                        while (resultSet.next()) {
                            String key = resultSet.getString("config_key");
                            if (SysConfigPolicy.isRuntimeKey(key)) {
                                map.put(key, resultSet.getString("config_value"));
                            }
                        }
                    }
                }
            }

            MutablePropertySources propertySources = environment.getPropertySources();
            PropertySource<?> source = new MapPropertySource(SOURCE_NAME, map);
            propertySources.addFirst(source);
        } catch (Exception e) {
            throw new PoetryRuntimeException(e);
        }
    }

    private void initDb(Connection connection, String initScript) throws SQLException, IOException {
        DatabaseMetaData metadata = connection.getMetaData();
        String catalog = connection.getCatalog();
        if (tableExists(metadata, catalog, "sys_config")) {
            return;
        }
        if (hasAnyTable(metadata, catalog)) {
            throw new PoetryRuntimeException("数据库已存在业务表但缺少 sys_config，已拒绝执行可能破坏数据的初始化脚本");
        }

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        populator.addScripts(resolver.getResources(initScript));
        populator.populate(connection);
    }

    private boolean tableExists(DatabaseMetaData metadata, String catalog, String tableName) throws SQLException {
        try (ResultSet tables = metadata.getTables(catalog, null, tableName, new String[]{"TABLE"})) {
            return tables.next();
        }
    }

    private boolean hasAnyTable(DatabaseMetaData metadata, String catalog) throws SQLException {
        try (ResultSet tables = metadata.getTables(catalog, null, "%", new String[]{"TABLE"})) {
            return tables.next();
        }
    }
}
