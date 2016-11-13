package com.lkb.ipservice.monitor.server;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.lkb.db.redis.util.JedisUtil;
import com.lkb.ipservice.server.Bootstrap;
import com.lkb.ipservice.server.HttpMessageHandler;
import com.lkb.ipservice.servie.IpService;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.apache.log4j.Logger;
import org.springframework.data.redis.core.StringRedisTemplate;
import redis.clients.jedis.Jedis;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by chenzhichao on 16/11/8.
 */
public class SocketServerHandler extends ChannelInboundHandlerAdapter {

    private static Logger log = Logger.getLogger(SocketServerHandler.class);
    private static Pattern host_pattern = Pattern.compile("/(.*):");
    private static final int PORT = 32188;
    private String host = "";
    private String host_port = "";
    private String un_priority_key = "";
    private StringRedisTemplate redisTemplate = JedisUtil.jedisClient.getRedisTemplate();
    public static IpService ipService = Bootstrap.ctx.getBean(IpService.class);

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        Matcher m = host_pattern.matcher(ctx.channel().remoteAddress().toString());
        m.find();
        host = m.group(1);
        host_port = host+":"+PORT;
        un_priority_key = "un_priority_" + host + ":" + PORT;
        ListenableFuture<Integer> future = Server.io_task_pool.submit(new Callable<Integer>() {
            @Override
            public Integer call() throws Exception {
                return ipService.spiderJdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_proxy_ip WHERE host=? AND port=? and available = FALSE",Integer.class,new Object[]{host,PORT});
            }
        });
        Futures.addCallback(future, new FutureCallback<Integer>() {
            @Override
            public void onSuccess(Integer count) {
                if(count > 0)
                    HttpMessageHandler.ipService_task_pool.execute(new Runnable() {
                        @Override
                        public void run() {
                            ipService.resumeFromDelete(host_port);
                        }
                    });
            }

            @Override
            public void onFailure(Throwable throwable) {
                log.error("数据库连接异常",throwable);
            }
        },Server.io_task_pool);
    }


    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        ListenableFuture<Integer> future = Server.io_task_pool.submit(new Callable<Integer>() {
            @Override
            public Integer call() throws Exception {
                return ipService.spiderJdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_proxy_ip WHERE host=? AND port=? and available = TRUE",Integer.class,new Object[]{host,PORT});
            }
        });
        Futures.addCallback(future, new FutureCallback<Integer>() {
            @Override
            public void onSuccess(Integer count) {
                if(count > 0)
                    HttpMessageHandler.ipService_task_pool.execute(new Runnable() {
                        @Override
                        public void run() {
                            ipService.deleteIp(host_port);
                        }
                    });
            }

            @Override
            public void onFailure(Throwable throwable) {
                log.error("数据库连接异常",throwable);
            }
        },Server.io_task_pool);
    }


    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        String[] rates = msg.toString().split("\\|");
        double cpuRate = 0;
        double memRate = 0;
        try {
            cpuRate = Double.parseDouble(rates[0]);
            memRate = Double.parseDouble(rates[1]);
        }catch (Exception e){
            log.error(host+"发来负载数据"+"解析失败",e);
        }
        if (cpuRate > 0.8 || memRate > 0.8) {
            log.warn("IP: " + host + ":" + PORT + "cpu负载:" + cpuRate + ",内存负载:" + memRate + ",降低使用频率");
            Server.io_task_pool.execute(new Runnable() {
                @Override
                public void run() {
                    final String ip = host + ":" + PORT;
                    if (redisTemplate.opsForValue().get(un_priority_key) == null) {
                        redisTemplate.opsForValue().set("un_priority_" + host + ":" + PORT, "1", 15, TimeUnit.MINUTES);
                        HttpMessageHandler.ipService_task_pool.execute(new Runnable() {
                            @Override
                            public void run() {
                                ipService.updateCount(ip, 10);
                            }
                        });
                    }
                }
            });
        }
    }


    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
    }
}
