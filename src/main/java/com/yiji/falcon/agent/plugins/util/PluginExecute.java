/*
 * www.yiji.com Inc.
 * Copyright (c) 2016 All Rights Reserved
 */
package com.yiji.falcon.agent.plugins.util;
/*
 * 修订记录:
 * guqiu@yiji.com 2016-06-24 11:12 创建
 */

import com.yiji.falcon.agent.common.AgentJobHelper;
import com.yiji.falcon.agent.plugins.*;
import com.yiji.falcon.agent.plugins.job.DetectPluginJob;
import com.yiji.falcon.agent.plugins.job.JDBCPluginJob;
import com.yiji.falcon.agent.plugins.job.JMXPluginJob;
import com.yiji.falcon.agent.plugins.job.SNMPPluginJob;
import com.yiji.falcon.agent.util.StringUtils;
import org.quartz.JobDataMap;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.DriverManager;
import java.util.Set;

/**
 * @author guqiu@yiji.com
 */
public class PluginExecute {

    private static final Logger logger = LoggerFactory.getLogger(PluginExecute.class);

    /**
     * 启动插件
     * @throws SchedulerException
     */
    public static void start() throws SchedulerException {
        //根据配置启动自发现功能
        AgentJobHelper.agentFlush();
        run();
    }

    /**
     * 运行插件
     */
    public static void run(){

        Set<Plugin> jmxPlugins = PluginLibraryHelper.getJMXPlugins();
        Set<Plugin> jdbcPlugins = PluginLibraryHelper.getJDBCPlugins();
        Set<Plugin> snmpv3Plugins = PluginLibraryHelper.getSNMPV3Plugins();
        Set<Plugin> detectPlugins = PluginLibraryHelper.getDetectPlugins();

        jmxPlugins.forEach(plugin -> {
            try {
                JMXPlugin jmxPlugin = (JMXPlugin) plugin;
                JobDataMap jobDataMap = new JobDataMap();
                //若jmxServerName有多个值,分别进行job启动
                for (String jmxServerName : ((JMXPlugin) plugin).jmxServerName().split(",")) {
                    if(!StringUtils.isEmpty(jmxServerName)){
                        String pluginName = String.format("%s-%s",jmxPlugin.pluginName(),jmxServerName);
                        jobDataMap.put("pluginName",pluginName);
                        jobDataMap.put("jmxServerName",jmxServerName);
                        jobDataMap.put("pluginObject",jmxPlugin);

                        AgentJobHelper.pluginWorkForJMX(pluginName,
                                jmxPlugin.activateType(),
                                jmxPlugin.step(),
                                JMXPluginJob.class,
                                pluginName,
                                jmxServerName,
                                jmxServerName,
                                jobDataMap);
                    }
                }
            } catch (Exception e) {
                logger.error("插件启动异常",e);
            }
        });
        snmpv3Plugins.forEach(plugin -> {
            try{
                SNMPV3Plugin snmpv3Plugin = (SNMPV3Plugin) plugin;
                JobDataMap jobDataMap = new JobDataMap();
                String pluginName = String.format("%s-%s",snmpv3Plugin.pluginName(),snmpv3Plugin.serverName());
                jobDataMap.put("pluginName",pluginName);
                jobDataMap.put("pluginObject",snmpv3Plugin);
                AgentJobHelper.pluginWorkForSNMPV3(snmpv3Plugin,pluginName,SNMPPluginJob.class,pluginName,snmpv3Plugin.serverName(),jobDataMap);
            }catch (Exception e){
                logger.error("插件启动异常",e);
            }
        });

        detectPlugins.forEach(plugin -> {
            try {
                DetectPlugin detectPlugin = (DetectPlugin) plugin;
                JobDataMap jobDataMap = new JobDataMap();
                String pluginName = plugin.pluginName();
                jobDataMap.put("pluginName",pluginName);
                jobDataMap.put("pluginObject",detectPlugin);
                AgentJobHelper.pluginWorkForDetect(detectPlugin,pluginName, DetectPluginJob.class,jobDataMap);
            }catch (Exception e){
                logger.error("插件启动异常",e);
            }
        });

        //设置JDBC超时为5秒
        DriverManager.setLoginTimeout(5);
        jdbcPlugins.forEach(plugin -> {
            try {
                JDBCPlugin jdbcPlugin = (JDBCPlugin) plugin;
                JobDataMap jobDataMap = new JobDataMap();
                String pluginName = String.format("%s-%s",jdbcPlugin.pluginName(),jdbcPlugin.serverName());
                jobDataMap.put("pluginName",pluginName);
                jobDataMap.put("pluginObject",jdbcPlugin);
                AgentJobHelper.pluginWorkForJDBC(jdbcPlugin,pluginName,JDBCPluginJob.class,pluginName,jdbcPlugin.serverName(),jobDataMap);
            } catch (Exception e) {
                logger.error("插件启动异常",e);
            }
        });

    }

}
