package com.lkb.ipservice.monitor.server;

import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.lkb.ipservice.server.Bootstrap;
import com.lkb.ipservice.servie.IpService;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by chenzhichao on 16/11/8.
 */
@Component
public class Server implements InitializingBean, DisposableBean {

    private volatile EventLoopGroup bossgroup = new NioEventLoopGroup(1);
    private volatile EventLoopGroup workgroup = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors() * 2);
    private static ExecutorService single_netty_server_pool = Executors.newFixedThreadPool(1);
    public static ListeningExecutorService io_task_pool = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(6));
    private static Logger log = Logger.getLogger(Server.class);

    public void start() {
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossgroup, workgroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 100)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(new ChannelInitializer<SocketChannel>() {

                    @Override
                    protected void initChannel(SocketChannel arg0) throws Exception {
                        ByteBuf byteBuf = Unpooled.copiedBuffer("$_".getBytes());
                        arg0.pipeline().addLast(new DelimiterBasedFrameDecoder(1024, byteBuf));
                        arg0.pipeline().addLast(new StringDecoder());
                        arg0.pipeline().addLast(new SocketServerHandler());
                    }
                });
        ChannelFuture cf = bootstrap.bind(8088);
        cf.addListener(new GenericFutureListener<Future<? super Void>>() {
            @Override
            public void operationComplete(Future<? super Void> future) throws Exception {
                if (future.isSuccess()) {
                    log.info("代理ip监控server启动成功");
                } else {
                    log.info("代理ip监控server启动失败,10s后重试");
                    Thread.sleep(10000);
                    single_netty_server_pool.execute(new Runnable() {
                        @Override
                        public void run() {
                            start();
                        }
                    });
                }
            }
        });
        if (cf.isSuccess())
            try {
                cf.channel().closeFuture().sync();
            } catch (Exception e) {
            } finally {
                bossgroup.shutdownGracefully();
                workgroup.shutdownGracefully();
            }
    }


    @Override
    public void destroy() throws Exception {
        Server.single_netty_server_pool.shutdown();
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        single_netty_server_pool.execute(new Runnable() {
            @Override
            public void run() {
                start();
            }
        });
    }
}
