/*
 * www.yiji.com Inc.
 * Copyright (c) 2016 All Rights Reserved
 */
package com.yiji.falcon.agent.jmx;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import com.yiji.falcon.agent.config.AgentConfiguration;
import com.yiji.falcon.agent.jmx.vo.JMXConnectionInfo;
import com.yiji.falcon.agent.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.remote.JMXConnector;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/*
 * 修订记录:
 * guqiu@yiji.com 2016-06-22 17:48 创建
 */

/**
 * 此类需要具体的监控对象(如ZK,Tomcat等进行继承)
 * @author guqiu@yiji.com
 */
public class JMXConnection {
    private static final Logger log = LoggerFactory.getLogger(JMXConnection.class);
    private static final Map<String,JMXConnectionInfo> connectCacheLibrary = new HashMap<>();//JMX的连接缓存
    private static final Map<String,Integer> serverConnectCount = new HashMap<>();//记录服务应有的JMX连接数
    private static final List<JMXConnector> closeRecord = new ArrayList<>();

    private String serverName;

    public JMXConnection(String serverName) {
        this.serverName = serverName;
    }

    /**
     * 删除JMX连接池连接
     * @param serverName
     * JMX服务名
     * @param pid
     * 进程id
     */
    public static void removeConnectCache(String serverName,int pid){
        String key = serverName + pid;
        if(connectCacheLibrary.remove(key) != null){
            //删除成功,更新serverConnectCount
            int count = serverConnectCount.get(serverName);
            serverConnectCount.put(serverName,count - 1);
            log.info("已清除JMX监控: {} , pid: {}",serverName,pid);
        }
    }

    /**
     * 根据服务名,返回该服务应有的JMX连接数
     * @param serverName
     * @return
     */
    public static int getServerConnectCount(String serverName){
        return serverConnectCount.get(serverName);
    }

    /**
     * 获取本地是否已开启指定的JMX服务
     * @param serverName
     * @return
     */
    public static boolean hasJMXServerInLocal(String serverName){
        if(!StringUtils.isEmpty(serverName)){
            List<VirtualMachineDescriptor> vms = VirtualMachine.list();
            for (VirtualMachineDescriptor desc : vms) {
                if(desc.displayName().contains(serverName)){
                    return true;
                }
            }
        }
        return false;
    }

    /**
     *
     * @throws IOException
     */
    public static void close() {
        for (JMXConnector jmxConnector : closeRecord) {
            try {
                jmxConnector.close();
            } catch (IOException e) {
                log.warn("",e);
            }
        }
    }

    /**
     * 获取指定服务名的本地JMX VM 描述对象
     * @param serverName
     * @return
     */
    private List<VirtualMachineDescriptor> getVmDescByServerName(String serverName){
        List<VirtualMachineDescriptor> vmDescList = new ArrayList<>();
        List<VirtualMachineDescriptor> vms = VirtualMachine.list();
        for (VirtualMachineDescriptor desc : vms) {
            if(desc.displayName().contains(serverName)){
                vmDescList.add(desc);
            }
        }
        return vmDescList;
    }

    /**
     * 获取JMX连接
     * @return
     * @throws IOException
     */
    public synchronized List<JMXConnectionInfo> getMBeanConnection(){
        if(StringUtils.isEmpty(serverName)){
            log.error("获取JMX连接的serverName不能为空");
            return new ArrayList<>();
        }

        List<VirtualMachineDescriptor> vmDescList = getVmDescByServerName(serverName);

        List<JMXConnectionInfo> connections = connectCacheLibrary.entrySet().
                stream().
                filter(entry -> entry.getKey().contains(serverName)).
                map(Map.Entry::getValue).
                collect(Collectors.toList());

        if(connections.isEmpty()){ //JMX连接池为空,进行连接获取
            int count = 0;
            for (VirtualMachineDescriptor desc : vmDescList) {
                JMXConnectUrlInfo jmxConnectUrlInfo = getConnectorAddress(desc);
                if (jmxConnectUrlInfo == null) {
                    log.error("应用 {} 的JMX连接URL获取失败",desc.displayName());
                    continue;
                }

                try {
                    connections.add(initJMXConnectionInfo(getJMXConnector(jmxConnectUrlInfo),desc));
                    log.debug("应用 {} JMX 连接已建立",serverName);
                    count++;
                } catch (Exception e) {
                    log.error("JMX 连接获取异常",e);
                }
            }

            if(count > 0){
                serverConnectCount.put(serverName,count);
            }
        }else if(connections.size() != vmDescList.size()){//若探测的JMX连接与连接池中的数量不一致,执行reset处理逻辑,reset后的结果,将在下一次取监控时生效
            resetMBeanConnection();
        }

        return connections;
    }

    private JMXConnector getJMXConnector(JMXConnectUrlInfo jmxConnectUrlInfo) throws IOException {
        JMXServiceURL url = new JMXServiceURL(jmxConnectUrlInfo.getRemoteUrl());
        JMXConnector connector;
        if(jmxConnectUrlInfo.isAuthentication()){
            connector = JMXConnectWithTimeout.connectWithTimeout(url,jmxConnectUrlInfo.getJmxUser()
                    ,jmxConnectUrlInfo.getJmxPassword(),10, TimeUnit.SECONDS);
        }else{
            connector = JMXConnectWithTimeout.connectWithTimeout(url,null,null,10, TimeUnit.SECONDS);
        }
        return connector;
    }

    /**
     * 重置jmx连接
     * @throws IOException
     */
    public synchronized void resetMBeanConnection() {
        if(StringUtils.isEmpty(serverName)){
            log.error("获取JMX连接的serverName不能为空");
        }

        //本地JMX连接中根据指定的服务名命中的VirtualMachineDescriptor
        List<VirtualMachineDescriptor> targetDesc = getVmDescByServerName(serverName);

        //若命中的target数量大于或等于该服务要求的JMX连接数,则进行重置连接池中的连接
        if(targetDesc.size() >= getServerConnectCount(serverName)){

            //清除当前连接池中的连接
            List<String> removeKey = connectCacheLibrary.keySet().stream().filter(key -> key.contains(serverName)).collect(Collectors.toList());
            removeKey.forEach(connectCacheLibrary::remove);

            //重新设置服务应有连接数
            int count = 0;
            //重新构建连接
            for (VirtualMachineDescriptor desc : targetDesc) {

                JMXConnectUrlInfo jmxConnectUrlInfo = getConnectorAddress(desc);
                if (jmxConnectUrlInfo == null) {
                    log.error("应用{}的JMX连接URL获取失败",serverName);
                    continue;
                }
                try {
                    initJMXConnectionInfo(getJMXConnector(jmxConnectUrlInfo),desc);
                    log.debug("应用 {} JMX 连接已建立,将在下一周期获取Metrics值时生效",serverName);
                    count++;
                } catch (IOException e) {
                    log.error("JMX 连接获取异常",e);
                }
            }
            serverConnectCount.put(serverName,count);
        }

    }



    private JMXConnectUrlInfo getConnectorAddress(VirtualMachineDescriptor desc){
        if(AgentConfiguration.INSTANCE.isAgentJMXLocalConnect()){
            String connectorAddress = AbstractJmxCommand.findJMXLocalUrlByProcessId(Integer.parseInt(desc.id()));
            if(connectorAddress != null){
                return new JMXConnectUrlInfo(connectorAddress);
            }
        }

        JMXConnectUrlInfo jmxConnectUrlInfo = AbstractJmxCommand.findJMXRemoteUrlByProcessId(Integer.parseInt(desc.id()),"127.0.0.1");
        if(jmxConnectUrlInfo != null){
            log.info("JMX Remote URL:{}",jmxConnectUrlInfo);
        }else if(!AgentConfiguration.INSTANCE.isAgentJMXLocalConnect()){
            log.warn("应用未配置JMX Remote功能,请给应用配置JMX Remote");
        }
        return jmxConnectUrlInfo;
    }

    /**
     * JMXConnectionInfo的初始化动作
     * @param connector
     * @param desc
     * @return
     * @throws IOException
     */
    private JMXConnectionInfo initJMXConnectionInfo(JMXConnector connector,VirtualMachineDescriptor desc) throws IOException {
        JMXConnectionInfo jmxConnectionInfo = new JMXConnectionInfo();
        jmxConnectionInfo.setCacheKeyId(desc.id());
        jmxConnectionInfo.setConnectionServerName(serverName);
        jmxConnectionInfo.setConnectionQualifiedServerName(desc.displayName());
        jmxConnectionInfo.setmBeanServerConnection(connector.getMBeanServerConnection());
        jmxConnectionInfo.setValid(true);
        jmxConnectionInfo.setPid(Integer.parseInt(desc.id()));

        connectCacheLibrary.put(serverName + desc.id(),jmxConnectionInfo);
        //添加关闭集合
        closeRecord.add(connector);
        return jmxConnectionInfo;
    }

}
