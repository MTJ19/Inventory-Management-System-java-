package com.inventory.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.*;
import java.util.*;

/** One shared runtime pool; legacy String constructors retain isolated H2 test connections. */
public final class Database implements AutoCloseable {
    private final String url,user,password,schema;
    private final HikariDataSource pool;
    private Database(String url,String user,String password,int size,boolean pooled,String schema) {
        this.url=url;this.user=user;this.password=password;this.schema=schema;
        if(pooled) {
            HikariConfig config=new HikariConfig();
            config.setJdbcUrl(url);if(!schema.isEmpty())config.addDataSourceProperty("currentSchema",schema);config.setUsername(user);config.setPassword(password);
            config.setMaximumPoolSize(size);config.setMinimumIdle(1);config.setConnectionTimeout(10000);
            config.setValidationTimeout(3000);config.setIdleTimeout(120000);config.setMaxLifetime(240000);config.setPoolName("inventory-db");
            if(postgres()) {config.setConnectionInitSql("SET lock_timeout = '10s'");config.addDataSourceProperty("connectTimeout","10");config.addDataSourceProperty("socketTimeout","30");config.addDataSourceProperty("ApplicationName","VoltEdge Inventory");}
            pool=new HikariDataSource(config);
        } else pool=null;
    }
    public static Database h2(String url) {if(!url.startsWith("jdbc:h2:")) throw new IllegalArgumentException("Use Database.fromEnvironment for PostgreSQL credentials");return new Database(url,"sa","",1,false,"");}
    public static Database fromEnvironment(Map<String,String> env,String localPath) {
        String url=env.getOrDefault("INVENTORY_JDBC_URL","").strip();
        if(url.isEmpty()) {
            if(env.getOrDefault("INVENTORY_ENV","development").equals("production")) throw new IllegalArgumentException("Production requires INVENTORY_JDBC_URL");
            return h2("jdbc:h2:file:"+java.nio.file.Path.of(localPath).toAbsolutePath()+";LOCK_TIMEOUT=10000");
        }
        if(!url.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException("INVENTORY_JDBC_URL must be a PostgreSQL JDBC URL");
        Properties properties=org.postgresql.Driver.parseURL(url,new Properties());
        if(properties==null) throw new IllegalArgumentException("Invalid PostgreSQL JDBC URL");
        if(properties.containsKey("user") || properties.containsKey("password")) throw new IllegalArgumentException("Set credentials in INVENTORY_DB_USER and INVENTORY_DB_PASSWORD, not the URL");
        boolean local=Set.of("localhost","127.0.0.1","[::1]","::1").contains(properties.getProperty("PGHOST"));
        if(!local || env.getOrDefault("INVENTORY_ENV","").equals("production")) {
            String sslmode = properties.getProperty("sslmode");
            if (!"require".equals(sslmode) && !"verify-full".equals(sslmode)) {
                throw new IllegalArgumentException(
                        "PostgreSQL JDBC URL must use sslmode=require or sslmode=verify-full");
            }
            if(properties.containsKey("sslfactory") || properties.containsKey("sslhostnameverifier")) throw new IllegalArgumentException("Custom TLS verification overrides are not permitted");
        }
        String user=env.getOrDefault("INVENTORY_DB_USER","");String password=env.getOrDefault("INVENTORY_DB_PASSWORD","");
        if(user.isBlank() || password.isEmpty()) throw new IllegalArgumentException("Set INVENTORY_DB_USER and INVENTORY_DB_PASSWORD");
        int size;
        try {size=Integer.parseInt(env.getOrDefault("INVENTORY_DB_POOL_SIZE","5"));}catch(NumberFormatException e){throw new IllegalArgumentException("Pool size must be an integer");}
        if(size<1 || size>20) throw new IllegalArgumentException("Pool size must be between 1 and 20");
        String schema=env.getOrDefault("INVENTORY_DB_SCHEMA","inventory");
        if(!schema.matches("[a-z][a-z0-9_]{0,62}") || Set.of("public","auth","storage","extensions","realtime").contains(schema))throw new IllegalArgumentException("Choose a dedicated application schema such as inventory");
        return new Database(url,user,password,size,true,schema);
    }
    public String schema() {return schema;}
    public boolean postgres() {return url.startsWith("jdbc:postgresql:");}
    public String label() {return postgres()?"PostgreSQL":"H2";}
    public String jdbcUrl() {return url;}
    public Connection connect() throws SQLException {return pool==null?DriverManager.getConnection(url,user,password):pool.getConnection();}
    public boolean ready() {try(var c=connect();var s=c.prepareStatement("SELECT 1 FROM inventory_write_lock WHERE id=1");var r=s.executeQuery()){return r.next();}catch(SQLException e){return false;}}
    @Override public void close() {if(pool!=null)pool.close();}
}
