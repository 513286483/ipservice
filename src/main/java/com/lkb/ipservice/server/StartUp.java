package com.lkb.ipservice.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.stream.ChunkedWriteHandler;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.GregorianCalendar;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by chenzhichao on 16/10/18.
 */
@Component
public class StartUp implements InitializingBean,DisposableBean {

	private Logger log = Logger.getLogger(StartUp.class);
    @Value("${application.url}")
    private String url;
    @Value("${application.port}")
    private int port;

    public void setUrl(String url) {
        this.url = url;
    }

    public void setPort(int port) {
        this.port = port;
    }

    private static ExecutorService single_pool = Executors.newFixedThreadPool(1);
    @Override
    public void afterPropertiesSet() throws Exception {
        single_pool.execute(new Runnable() {
            @Override
            public void run() {
                EventLoopGroup bossGroup = new NioEventLoopGroup(1);
                EventLoopGroup workerGroup = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors() * 2);
                ServerBootstrap bootstrap = new ServerBootstrap();
                try {
                    bootstrap.group(bossGroup, workerGroup)
                            .channel(NioServerSocketChannel.class)
                            .childOption(ChannelOption.TCP_NODELAY, true)
                            .childHandler(new ChannelInitializer<SocketChannel>() {
                                @Override
                                protected void initChannel(SocketChannel ch) throws Exception {
                                    ch.pipeline().addLast("http-decoder",
                                            new HttpRequestDecoder());
                                    ch.pipeline().addLast("http-aggregator",
                                            new HttpObjectAggregator(65536));
                                    ch.pipeline().addLast("http-encoder",
                                            new HttpResponseEncoder());
                                    ch.pipeline().addLast("http-chunked",
                                            new ChunkedWriteHandler());
                                    ch.pipeline().addLast("fileServerHandler",
                                            new HttpMessageHandler());
                                }
                            });
                    bootstrap.bind(port).sync().channel().closeFuture().sync();
                    log.info("netty http server start at: " + new GregorianCalendar().getTime());
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    bossGroup.shutdownGracefully();
                    workerGroup.shutdownGracefully();
                }
            }
        });
    }

    @Override
    public void destroy() throws Exception {
        HttpMessageHandler.ipService_task_pool.shutdown();
        single_pool.shutdown();
    }
}
