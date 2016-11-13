package com.lkb.ipservice.server;

import java.util.Date;

import org.apache.log4j.Logger;
import org.springframework.context.support.ClassPathXmlApplicationContext;


public class Bootstrap {

    public static ClassPathXmlApplicationContext ctx;
    private static Logger log = Logger.getLogger(Bootstrap.class);
    public static void main(String[] args) {
        ctx = new ClassPathXmlApplicationContext("applicationContext.xml");//先把context也就是spring容器new出来
        if (ctx != null) {
            ctx.registerShutdownHook();//注册关闭钩子
            Date stateDate = new Date(ctx.getStartupDate());
            log.info("crawler-ipservice Application has started at " + stateDate);
        }
    }
}
