/*
 * www.yiji.com Inc.
 * Copyright (c) 2016 All Rights Reserved
 */
package com.yiji.falcon.agent.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringTokenizer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/*
 * 修订记录:
 * guqiu@yiji.com 2016-06-22 17:48 创建
 */

/**
 * 命令执行
 * @author guqiu@yiji.com
 */
public class CommandUtilForUnix {

    private static final Logger logger = LoggerFactory.getLogger(CommandUtilForUnix.class);

    /**
     * 命令执行的返回结果值类
     */
    public static class ExecuteResult{
        /**
         * 是否执行成功
         */
        public boolean isSuccess = false;
        /**
         * 执行命令的返回结果
         */
        public String msg = "";

        @Override
        public String toString() {
            return "ExecuteResult{" +
                    "isSuccess=" + isSuccess +
                    ", msg='" + msg + '\'' +
                    '}';
        }
    }

    /**
     * 获取指定进程的命令行目录
     * @param pid
     * @return
     * @throws IOException
     */
    public static String getCmdDirByPid(int pid) throws IOException {
        String cmd = "lsof -p " + pid;
        CommandUtilForUnix.ExecuteResult executeResult = CommandUtilForUnix.execWithReadTimeLimit(cmd,false,10, TimeUnit.SECONDS);
        String msg = executeResult.msg;
        String[] ss = msg.split("\n");
        for (String s : ss) {
            if(s.toLowerCase().contains("cwd")){
                String[] split = s.split("\\s+");
                return split[split.length - 1];
            }
        }

        return null;
    }


    /**
     * 执行命令,给定最大的命令结果读取时间
     * @param execTarget
     * @param cmd
     * @param cmdSep
     * 是否对cmd进行空格分隔
     * @param maxReadTime
     * @param unit
     * @return
     * @throws IOException
     */
    public static ExecuteResult execWithReadTimeLimit(String execTarget,String cmd,boolean cmdSep, long maxReadTime, TimeUnit unit) throws IOException {
        return exec(execTarget,cmd,cmdSep,unit.toMillis(maxReadTime));
    }

    /**
     * 执行命令,给定最大的命令结果读取时间
     * @param cmd
     * @param cmdSep
     * 是否对cmd进行空格分隔
     * @param maxReadTime
     * @param unit
     * @return
     * @throws IOException
     */
    public static ExecuteResult execWithReadTimeLimit(String cmd,boolean cmdSep, long maxReadTime, TimeUnit unit) throws IOException {
        return execWithReadTimeLimit(null,cmd,cmdSep,maxReadTime,unit);
    }

    /**
     * 执行命令,指定执行体
     * @param execTarget
     * 指定执行体,null则使用默认执行体(/bin/sh)
     * @param cmd
     * @param cmdSep
     * 是否对cmd进行空格分隔
     * @param timeout
     * @param unit
     * @return
     * @throws IOException
     */
    public static ExecuteResult execWithTimeOut(String execTarget,String cmd,boolean cmdSep, long timeout, TimeUnit unit) throws IOException {
        final ExecuteResult[] executeResult = {new ExecuteResult()};
        final BlockingQueue<Object> blockingQueue = new ArrayBlockingQueue<>(1);
        ExecuteThreadUtil.execute(() -> {
            try {
                executeResult[0] = exec(execTarget,cmd,cmdSep,0);
                if (!blockingQueue.offer(executeResult[0]))
                    logger.error("阻塞队列插入失败");
            } catch (Throwable t) {
                blockingQueue.offer(t);
            }
        });

        Object result = BlockingQueueUtil.getResult(blockingQueue,timeout,unit);

        if (result == null)
            throw new IOException("Command \"" + cmd + "\" execute timeout with " + timeout + " " + unit.name());
        if (result instanceof ExecuteResult)
            return (ExecuteResult) result;
        try {
            throw (Throwable) result;
        } catch (IOException | RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IOException(e.toString(), e);
        }
    }

    /**
     * 执行命令,使用默认执行体(/bin/sh)
     * @param cmd
     * @param cmdSep
     * 是否对cmd进行空格分隔
     * @param timeout
     * @param unit
     * @return
     * @throws IOException
     */
    public static ExecuteResult execWithTimeOut(String cmd,boolean cmdSep, long timeout, TimeUnit unit) throws IOException {
        return execWithTimeOut(null,cmd,cmdSep,timeout,unit);
    }

    /**
     * 执行命令
     * @param execTarget
     * 执行体
     * 默认 /bin/sh
     * @param cmd
     * 待执行的命令
     * @param cmdSep
     * 是否对cmd进行空格分隔
     * @param waitTime
     * 若大于0,则代表开启等待命令结果输出的最大等待时间(毫秒)
     * @return
     * @throws IOException
     */
    private static ExecuteResult exec(String execTarget,String cmd,boolean cmdSep,long waitTime) throws IOException {
        ExecuteResult result = new ExecuteResult();

        List<String> shList = new ArrayList<>();
        if(execTarget == null){
            execTarget = "/bin/sh";
            shList.add(execTarget);
            shList.add("-c");
        }else{
            shList.add(execTarget);
        }
        if(cmdSep){
            Collections.addAll(shList, cmd.split("\\s"));
        }else{
            shList.add(cmd);
        }

        logger.info("执行命令 : {} {}",execTarget,cmd);

        ProcessBuilder pb = new ProcessBuilder(shList);
        Process process = pb.start();

        long startTime = System.currentTimeMillis();
        boolean readTimeout = false;

        StringBuilder sb = new StringBuilder();
        try(ByteArrayOutputStream resultOutStream = new ByteArrayOutputStream();
            InputStream errorInStream = new BufferedInputStream(process.getErrorStream());
            InputStream processInStream = new BufferedInputStream(process.getInputStream())){

            int num;
            byte[] bs = new byte[1024];

            while ((num = errorInStream.read(bs)) != -1) {
                resultOutStream.write(bs, 0, num);
                sb.append(new String(resultOutStream.toByteArray(),"utf-8"));
                if(waitTime > 0 && System.currentTimeMillis() - startTime >= waitTime){
                    readTimeout = true;
                    break;
                }
            }
            while ((num = processInStream.read(bs)) != -1) {
                resultOutStream.write(bs, 0, num);
                sb.append(new String(resultOutStream.toByteArray(),"utf-8"));
                if(readTimeout || (waitTime > 0 && System.currentTimeMillis() - startTime >= waitTime)){
                    readTimeout = true;
                    break;
                }
            }

            result.msg = sb.toString();
        }

        try {
            if(readTimeout){
                logger.warn("命令 {} 执行读取 {} 毫秒输出的结果 : {}",cmd,waitTime,result.msg);
                result.isSuccess = true;
            }else if(process.waitFor() != 0){
                logger.warn("命令 {} 执行失败 : {}",cmd,result.msg);
                result.isSuccess = false;
            }else {
                result.isSuccess = true;
            }
        } catch (InterruptedException e) {
            logger.error("命令 {} 执行异常",cmd,e);
        }

        process.destroy();

        return result;
    }

    /**
     * 进行Ping探测
     * @param address
     * 需要Ping的地址
     * @param count
     * Ping的次数
     * @return
     * 返回Ping的平均延时
     * 返回 -1 代表 Ping超时
     * 返回 -2 代表 命令执行失败
     */
    public static PingResult ping(String address,int count) throws IOException {
        PingResult pingResult = new PingResult();
        String commend = String.format("ping -c %d %s",count,address);
        CommandUtilForUnix.ExecuteResult executeResult = CommandUtilForUnix.execWithReadTimeLimit(commend,false,30,TimeUnit.SECONDS);

        if(executeResult.isSuccess){
            List<Float> times = new ArrayList<>();
            String msg = executeResult.msg;
            for (String line : msg.split("\n")) {
                for (String ele : line.split(" ")) {
                    if(ele.toLowerCase().contains("time=")){
                        float time = Float.parseFloat(ele.replace("time=",""));
                        times.add(time);
                    }
                }
            }

            if(times.isEmpty()){
                logger.warn(String.format("ping 地址 %s 无法连通",address));
                pingResult.resultCode = -1;
            }else{
                float sum = 0;
                for (Float time : times) {
                    sum += time;
                }
                pingResult.resultCode = 1;
                pingResult.avgTime = Maths.div(sum,times.size(),3);
                pingResult.successCount = times.size();
            }

        }else{
            logger.error("命令{}执行失败",commend);
            pingResult.resultCode = -2;
        }

        return pingResult;
    }

    /**
     * 从 /etc/profile 文件获取JAVA_HOME
     * @return
     * null : 获取失败
     * @throws IOException
     */
    public static String getJavaHomeFromEtcProfile() throws IOException {
        ExecuteResult executeResult = execWithReadTimeLimit("cat /etc/profile",false,10,TimeUnit.SECONDS);
        if(!executeResult.isSuccess){
            return null;
        }
        String msg = executeResult.msg;
        StringTokenizer st = new StringTokenizer(msg,"\n",false);
        while( st.hasMoreElements() ){
            String split = st.nextToken().trim();
            if(!StringUtils.isEmpty(split)){
                if(split.contains("JAVA_HOME")){
                    String[] ss = split.split("=");
                    List<String> list = new ArrayList<>();
                    for (String s : ss) {
                        if(!StringUtils.isEmpty(s)){
                            list.add(s);
                        }
                    }
                    return list.get(list.size() - 1);
                }
            }
        }

        return null;
    }

    public static class PingResult{
        /**
         * 执行结果
         * 返回  1 代表执行成功
         * 返回 -1 代表 Ping超时
         * 返回 -2 代表 命令执行失败
         */
        public int resultCode;
        /**
         * 成功返回ping延迟的次数
         */
        public int successCount;
        /**
         * ping的平均延迟值
         */
        public double avgTime;

        @Override
        public String toString() {
            return "PingResult{" +
                    "resultCode=" + resultCode +
                    ", successCount=" + successCount +
                    ", avgTime=" + avgTime +
                    '}';
        }
    }

}