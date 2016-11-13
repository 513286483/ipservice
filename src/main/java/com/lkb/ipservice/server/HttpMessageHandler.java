package com.lkb.ipservice.server;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.alibaba.fastjson.JSONObject;
import org.apache.log4j.Logger;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.lkb.db.redis.util.JedisUtil;
import com.lkb.ipservice.model.Ip;
import com.lkb.ipservice.servie.IpService;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;


public class HttpMessageHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    public static ExecutorService ipService_task_pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() + 1);
    private static Logger log = Logger.getLogger(HttpMessageHandler.class);
    private static HashSet<String> method_set = new HashSet<>();
    public static final boolean isSaveLoginInfo;
    private static StringRedisTemplate redisTemplate = JedisUtil.jedisClient.getRedisTemplate();
    public static IpService ipService = Bootstrap.ctx.getBean(IpService.class);
    private static final String SOURCE = "source";
    private static final String USER = "user";
    private static final String IP = "ip";

    static {
        method_set.add("login");
        method_set.add("init");
        isSaveLoginInfo = true;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
        log.error("netty异常", cause);
        ctx.close();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
        if (msg.method().equals(HttpMethod.GET))
            handleGet(ctx, msg);
        else if (msg.method().equals(HttpMethod.POST))
            handlePost(ctx, msg);
        else
            ctx.close();
    }

    private void handlePost(final ChannelHandlerContext ctx, final FullHttpRequest msg) {
        final Map<String, String> requestParams = getPostRequestParams(msg);
        final boolean isKeepAlive = HttpUtil.isKeepAlive(msg);
        ipService_task_pool.execute(new Runnable() {

            @Override
            public void run() {
                String method = requestParams.get("method");
                FullHttpResponse fullHttpResponse = getPostBaseResponse(isKeepAlive);
                JSONObject jsonObject = new JSONObject();
                switch (method) {
                    case "getIp":
                        Ip ip;
                        try {
                            ip = ipService.getIp(requestParams.get(SOURCE), requestParams.get(USER));
                        } catch (Exception e) {
                            log.error(requestParams.get(SOURCE) + "|" + requestParams.get(USER) + "获取IP失败", e);
                            ip = new Ip();
                        }
                        fullHttpResponse.content().writeBytes(ip.toJSON().toString().getBytes());
                        break;
                    case "del":
                        ipService.deleteIp(requestParams.get(IP));
                        break;
                    case "resumeFromDelete":
                        ipService.resumeFromDelete(requestParams.get(IP));
                        break;
                    case "insert":
                        ipService.insertIp(requestParams.get(IP));
                        break;
                    case "deleteIpFromSource":
                        ipService.deleteIpFromSource(requestParams.get(IP),requestParams.get(SOURCE));
                        break;
                    case "resumeFromBlacklist":
                        ipService.resumeFromBlacklist(requestParams.get(IP),requestParams.get(SOURCE));
                        break;
                    case "reload":
                        try {
                            ipService.afterPropertiesSet();
                        } catch (Exception e) {
                            log.error("重新加载失败", e);
                        }
                        break;
                }
                if (!method.equals("getIp")) {
                    jsonObject.put("status", "收到请求");
                    fullHttpResponse.content().writeBytes(jsonObject.toJSONString().getBytes());
                }
                ctx.writeAndFlush(fullHttpResponse);
            }
        });
    }

    public static FullHttpResponse getPostBaseResponse(boolean isKeepalive) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
        if (isKeepalive)
            response.headers().add(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        else
            response.headers().add(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        return response;
    }

    private void handleGet(final ChannelHandlerContext ctx, final FullHttpRequest msg) {

    }


    public Map<String, String> getPostRequestParams(FullHttpRequest msg) {
        HttpPostRequestDecoder decoder = new HttpPostRequestDecoder(msg);
        HashMap<String, String> params = new HashMap<String, String>();
        for (InterfaceHttpData data : decoder.getBodyHttpDatas()) {
            if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.Attribute) {
                Attribute attribute = (Attribute) data;
                try {
                    params.put(attribute.getName(), attribute.getValue());

                } catch (IOException e) {
                    log.error(e);
                }
            }
        }
        return params;
    }
}