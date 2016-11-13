package com.lkb.ipservice.servie;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.lkb.db.redis.util.JedisUtil;
import com.lkb.ipservice.model.BlackIp;
import com.lkb.ipservice.model.Ip;

@Service
public class IpService implements InitializingBean, DisposableBean {

    @Resource(name = "spiderJdbcTemplate")
    public JdbcTemplate spiderJdbcTemplate;
    private StringRedisTemplate stringRedisTemplate = JedisUtil.jedisClient.getRedisTemplate();
    public static final String TOTAL = "total";
    @SuppressWarnings("unused")
    private RedisTemplate<String, Object> originRedisTemplate = JedisUtil.jedisClient.getOriginRedisTemplate();

    private Map<String, Ip> host_port_ip_map = new HashMap<>();// 存储格式为"host:port"->ip,存储所有可用(available)ip
    private TreeSet<Ip> available_Ips = createTreeSetInstance();// 存储所有可用ip的set

    private Map<String, LinkedList<TreeSet<Ip>>> source_setlist_map = new HashMap<>();// 存放所有的source的treeset_list

    private Map<String, HashSet<Ip>> source_blackIpset_map = new HashMap<>();//存放被单个源禁用的ip和全局禁用的ip
    private HashMap<String, Ip> not_available_ips = new HashMap<>();//存放全局禁用ip的map
    private static ListeningExecutorService io_task_pool = MoreExecutors
            .listeningDecorator(Executors.newFixedThreadPool(5));
    private static Logger log = Logger.getLogger(IpService.class);
    public final Object lock = new Object();

    public Ip getIp(final String source, final String user) {
        // 存入格式为key=source|user|ip value=host:port
        final String current_redis_key = source + "|" + user + "|" + "ip";
        String current_user_source_ip = stringRedisTemplate.opsForValue().get(current_redis_key);
        if (current_user_source_ip != null) {// 如果黑名单中包含这个IP，则置为null，进入else
            Ip ip = host_port_ip_map.get(current_user_source_ip);
            HashSet<Ip> current_source_blackIp_set = null;
            if (ip == null || ((current_source_blackIp_set = source_blackIpset_map.get(source)) != null && (current_source_blackIp_set.contains(ip))) || source_blackIpset_map.get(TOTAL).contains(ip)) {
                current_user_source_ip = null;
                io_task_pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        stringRedisTemplate.delete(current_redis_key);
                    }
                });
            }
        }
        final Ip ip;
        synchronized (lock) {
            LinkedList<TreeSet<Ip>> list = source_setlist_map.get(source);
            if (list == null) {
                list = new LinkedList<>();
                TreeSet<Ip> set = createTreeSetInstance();
                set.addAll(available_Ips);
                HashSet<Ip> blackIpSet = source_blackIpset_map.get(source);
                set.removeAll(source_blackIpset_map.get(TOTAL));
                if (blackIpSet != null)
                    set.removeAll(blackIpSet);
                for (Ip ip1 : set)
                    ip1.getSourceMap().put(source, set);
                list.add(set);
                source_setlist_map.put(source, list);
            }
            TreeSet<Ip> current_set;
            if (current_user_source_ip != null) {
                ip = host_port_ip_map.get(current_user_source_ip);
                current_set = ip.getSourceMap().get(source);
                current_set.remove(ip);
            } else {
                current_set = list.getFirst();
                ip = current_set.pollFirst();
            }
            TreeSet<Ip> next_set = getNextTreeSet(list, current_set);
            if (next_set == null) {
                next_set = createTreeSetInstance();
                list.add(next_set);
            }
            if (current_set.size() == 0 && list.indexOf(current_set) == 0)
                list.remove(current_set);
            ip.getCountMap().put(source, ip.getSourceCount(source) + 1);
            ip.getCountMap().put(TOTAL, ip.getSourceCount(TOTAL) + 1);
            next_set.add(ip);
            ip.getSourceMap().put(source, next_set);
            ip.updateCount(source);
            log.info(source + ":" + list.toString());
        }

        io_task_pool.execute(new Runnable() {
            @Override
            public void run() {
                stringRedisTemplate.opsForValue().set(current_redis_key, ip.getHost() + ":" + ip.getPort(), 15,
                        TimeUnit.MINUTES);
            }
        });
        log.info(source + "|" + user + " get ip: " + ip.getHost() + ":" + ip.getPort());
        return ip;
    }

    public void insertIp(final String host_port) {
        String[] arr = host_port.split(":");
        final String host = arr[0];
        final int port = Integer.parseInt(arr[1]);
        if (host_port_ip_map.get(host_port) != null)
            return;
        synchronized (lock) {
            if (not_available_ips.get(host_port) != null)
                return;
            for (HashSet<Ip> set : source_blackIpset_map.values()) {
                for (Ip ip : set)
                    if (ip.getHost().equals(host) || ip.getPort().equals(port))
                        return;
            }
        }
        ListenableFuture<Ip> future = io_task_pool.submit(new Callable<Ip>() {

            @Override
            public Ip call() throws Exception {
                KeyHolder keyHolder = new GeneratedKeyHolder();
                spiderJdbcTemplate.update(new PreparedStatementCreator() {

                    @Override
                    public PreparedStatement createPreparedStatement(Connection con) throws SQLException {
                        PreparedStatement ps = con.prepareStatement("insert into t_proxy_ip (host,port) values (?,?)",
                                PreparedStatement.RETURN_GENERATED_KEYS);
                        ps.setString(1, host);
                        ps.setInt(2, port);
                        return ps;
                    }
                }, keyHolder);
                int id = keyHolder.getKey().intValue();
                return new Ip(id, host, port, true);
            }
        });
        Futures.addCallback(future, new FutureCallback<Ip>() {

            @Override
            public void onSuccess(Ip ip) {
                synchronized (lock) {
                    available_Ips.add(ip);
                    ip.getSourceMap().put(TOTAL, available_Ips);
                    host_port_ip_map.put(host_port, ip);
                    for (Map.Entry<String, LinkedList<TreeSet<Ip>>> entry : source_setlist_map.entrySet()) {
                        TreeSet<Ip> set = entry.getValue().getFirst();
                        set.add(ip);
                        ip.getSourceMap().put(entry.getKey(), set);
                    }
                }
                log.error("添加ip: " + host_port + "成功");
            }

            @Override
            public void onFailure(Throwable t) {
                log.error("添加ip: " + host_port + "失败", t);
            }
        }, io_task_pool);
    }

    public void deleteIp(final String host_port) {
        final Ip ip;
        if ((ip = host_port_ip_map.get(host_port)) == null)//如果可用状态的ip中不包含这个ip,return
            return;
        ListenableFuture<Object> future = io_task_pool.submit(new Callable<Object>() {

            @Override
            public Object call() {
                spiderJdbcTemplate.update("update t_proxy_ip set available = false where id = ?",
                        new Object[]{ip.getId()});//设置available为false
                return null;
            }
        });
        Futures.addCallback(future, new FutureCallback<Object>() {

            @Override
            public void onSuccess(Object result) {
                synchronized (lock) {
                    available_Ips.remove(ip);// 从可用ip的set中删除
                    host_port_ip_map.remove(host_port);//从全局可用map中移除
                    ip.removeFromAllSet();// 从这个ip所在的所有set中删除
                    ip.setCountMap(new HashMap<String, Integer>());
                    source_blackIpset_map.get(TOTAL).add(ip);//加入黑名单
                    not_available_ips.put(host_port, ip);//加入全局禁用的map
                }
                log.info("IP: " + host_port + "已禁用");
            }

            @Override
            public void onFailure(Throwable t) {
                log.info("IP: " + host_port + "禁用失败");
            }
        }, io_task_pool);
    }

    public void resumeFromDelete(final String host_port) {
        final Ip ip = not_available_ips.get(host_port);
        if (ip == null)
            return;
        ListenableFuture<Object> future = io_task_pool.submit(new Callable<Object>() {

            @Override
            public Object call() {
                spiderJdbcTemplate.update("update t_proxy_ip set available = TRUE where id = ?",
                        new Object[]{ip.getId()});
                return null;
            }
        });
        Futures.addCallback(future, new FutureCallback<Object>() {

            @Override
            public void onSuccess(Object result) {
                synchronized (lock) {
                    available_Ips.add(ip);//加入全局可用ip的set
                    source_blackIpset_map.get(TOTAL).remove(ip);//从黑名单map中移除
                    not_available_ips.remove(host_port);//从全局禁用set中移除
                    host_port_ip_map.put(host_port, ip);//加入全局可用map
                    ip.getSourceMap().put(TOTAL, available_Ips);
                    for (Map.Entry<String, LinkedList<TreeSet<Ip>>> entry : source_setlist_map.entrySet()) {
                        HashSet<Ip> current_blacklist_set = source_blackIpset_map.get(entry.getKey());
                        if (current_blacklist_set == null || !current_blacklist_set.contains(ip)) {//此处判断是因为怕有的全局禁用ip本身也被某个源禁用
                            TreeSet<Ip> set = entry.getValue().getFirst();
                            set.add(ip);
                            ip.getSourceMap().put(entry.getKey(), set);
                        }
                    }
                }
                log.info("IP: " + host_port + "已启用");
            }

            @Override
            public void onFailure(Throwable t) {
                log.info("IP: " + host_port + "启用失败");
            }
        }, io_task_pool);
    }

    public void deleteIpFromSource(final String host_port, final String source) {
        final Ip ip = host_port_ip_map.get(host_port);
        if (ip == null)
            return;
        ListenableFuture<Object> future = io_task_pool.submit(new Callable<Object>() {

            @Override
            public Object call() throws Exception {
                spiderJdbcTemplate.update("insert into t_proxy_ip_blacklist (host,port,source) values(?,?,?)",
                        new Object[]{ip.getHost(), ip.getPort(), source});
                return null;
            }

        });
        Futures.addCallback(future, new FutureCallback<Object>() {

            @Override
            public void onSuccess(Object result) {
                synchronized (lock) {
                    HashSet<Ip> blackIpSet = source_blackIpset_map.get(source);
                    if (blackIpSet == null) {
                        blackIpSet = new HashSet<>();
                        source_blackIpset_map.put(source, blackIpSet);
                    }
                    blackIpSet.add(ip);
                    List<TreeSet<Ip>> list = source_setlist_map.get(source);
                    TreeSet<Ip> set = ip.getSourceMap().get(source);
                    if (list == null || set == null)
                        return;
                    set.remove(ip);
                    ip.getSourceMap().remove(source);
                    if (set.size() == 0 && list.indexOf(set) == 0)
                        list.remove(0);
                }
                log.info("已将ip: " + host_port + "加入" + source + "黑名单");
            }

            @Override
            public void onFailure(Throwable t) {
                log.error("未能将ip: " + host_port + "加入" + source + "黑名单");
            }

        }, io_task_pool);

    }

    public void resumeFromBlacklist(final String host_port, final String source) {
        final Ip ip = host_port_ip_map.get(host_port);
        if (ip == null)
            return;
        ListenableFuture<Object> future = io_task_pool.submit(new Callable<Object>() {

            @Override
            public Object call() throws Exception {
                spiderJdbcTemplate.update("delete from t_proxy_ip_blacklist where host=? and port=? and source=?",
                        new Object[]{ip.getHost(), ip.getPort(), source});
                return null;
            }
        });
        Futures.addCallback(future, new FutureCallback<Object>() {

            @Override
            public void onSuccess(Object result) {
                synchronized (lock) {
                    TreeSet<Ip> first_set = source_setlist_map.get(source).getFirst();
                    first_set.add(ip);
                    ip.getSourceMap().put(source, first_set);
                }
                log.info(source + " ip: " + host_port + "已解封");
            }

            @Override
            public void onFailure(Throwable t) {
                log.info(source + " ip: " + host_port + "解封失败");
            }
        }, io_task_pool);
    }

    private static TreeSet<Ip> getNextTreeSet(LinkedList<TreeSet<Ip>> list, TreeSet<Ip> set) {
        if (list.size() == 0)
            return null;
        TreeSet<Ip> temp = null;
        Iterator<TreeSet<Ip>> iterator = list.iterator();
        while (iterator.hasNext()) {
            temp = iterator.next();
            if (temp == set)
                if (iterator.hasNext())
                    return iterator.next();
                else
                    return null;
        }
        return null;
    }

    private static TreeSet<Ip> createTreeSetInstance() {
        return new TreeSet<Ip>(new Comparator<Ip>() {

            @Override
            public int compare(Ip o1, Ip o2) {
                int o1total = o1.getSourceCount(TOTAL);
                int o2total = o2.getSourceCount(TOTAL);
                if (o1total != o2total)
                    return o1total - o2total;
                return o1.getId() - o2.getId();
            }

        });
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        synchronized (lock) {
            host_port_ip_map.clear();
            available_Ips.clear();
            not_available_ips.clear();
            source_blackIpset_map.clear();
            source_setlist_map.clear();
            List<Ip> ips = spiderJdbcTemplate.query("select * from t_proxy_ip", new Ip());
            for (Ip ip : ips)
                if (ip.getAvailable()) {
                    host_port_ip_map.put(ip.getHost() + ":" + ip.getPort(), ip);
                    available_Ips.add(ip);
                    ip.getSourceMap().put(TOTAL, available_Ips);
                } else
                    not_available_ips.put(ip.getHost() + ":" + ip.getPort(), ip);
            List<BlackIp> blackIps = spiderJdbcTemplate.query("select * from t_proxy_ip_blacklist", new BlackIp());
            for (BlackIp blackIp : blackIps) {
                if (blackIp.getSource() != null) {
                    HashSet<Ip> set = source_blackIpset_map.get(blackIp.getSource());
                    if (set == null) {
                        set = new HashSet<>();
                        source_blackIpset_map.put(blackIp.getSource(), set);
                    }
                    Ip b_ip = host_port_ip_map.get(blackIp.getHost() + ":" + blackIp.getPort());
                    if (b_ip != null)
                        set.add(b_ip);
                }
            }
            source_blackIpset_map.put(TOTAL, new HashSet<Ip>(not_available_ips.values()));
            log.info("加载完成");
        }
    }

    @Override
    public void destroy() throws Exception {
        io_task_pool.shutdown();
    }

    public void updateCount(String host_port, int steps) {
        final Ip ip = host_port_ip_map.get(host_port);
        //如果本来没有这个ip或者这个IP本来就不可用或者redis中已存在这个key,return
        if (ip == null || not_available_ips.get(host_port) != null || stringRedisTemplate.opsForValue().get("un_priority_" + host_port) != null)
            return;
        synchronized (lock) {
            for (TreeSet<Ip> set : ip.getSourceMap().values())
                set.remove(ip);
            ip.getCountMap().put(TOTAL, ip.getSourceCount(TOTAL) + steps);
            for (TreeSet<Ip> set : ip.getSourceMap().values())
                set.add(ip);
        }
    }
}
