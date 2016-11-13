package com.lkb.ipservice.model;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

import com.lkb.ipservice.servie.IpService;
import org.springframework.jdbc.core.RowMapper;

import com.alibaba.fastjson.JSONObject;

/**
 * Created by chenzhichao on 16/11/1.
 */
public class Ip implements RowMapper<Ip> {

    private Integer id;
    private String host;
    private Integer port;
    private Boolean available;
    private HashMap<String, Integer> countMap = new HashMap<>();
    private HashMap<String, TreeSet<Ip>> sourceMap = new HashMap<>();

    public HashMap<String, Integer> getCountMap() {
        return countMap;
    }

    public void setCountMap(HashMap<String, Integer> countMap) {
        this.countMap = countMap;
    }

    public HashMap<String, TreeSet<Ip>> getSourceMap() {
        return sourceMap;
    }

    public void setSourceMap(HashMap<String, TreeSet<Ip>> sourceMap) {
        this.sourceMap = sourceMap;
    }

    public Ip() {
        super();
    }

    public Ip(Integer id, String host, Integer port, Boolean available) {
        super();
        this.id = id;
        this.host = host;
        this.port = port;
        this.available = available;
    }

    public Boolean getAvailable() {
        return available;
    }

    public void setAvailable(Boolean available) {
        this.available = available;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public int getSourceCount(String source) {
        Integer count = countMap.get(source);
        if (count == null) {
            count = 0;
            countMap.put(source, count);
        }
        return count;
    }

    public Ip mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Ip(rs.getInt("id"), rs.getString("host"), rs.getInt("port"),rs.getBoolean("available"));
    }

//	@Override
//	public boolean equals(Object obj) {
//		if (!(obj instanceof Ip))
//			return false;
//		Ip ip = (Ip) obj;
//		return this.host.equals(ip.host) && this.port.equals(ip.port);
//	}

    public JSONObject toJSON() {
        JSONObject object = new JSONObject();
        object.put("host", this.host);
        object.put("port", this.port);
        return object;
    }

    public void updateCount(String source) {
        for (Map.Entry<String, TreeSet<Ip>> entry : sourceMap.entrySet())
            if (!entry.getKey().equals(source)) {
                getCountMap().put(IpService.TOTAL, getSourceCount(IpService.TOTAL) - 1);
                entry.getValue().remove(this);
                getCountMap().put(IpService.TOTAL, getSourceCount(IpService.TOTAL) + 1);
                entry.getValue().add(this);
            }
    }

    public void removeFromAllSet() {
        for (TreeSet<Ip> set : sourceMap.values())
            set.remove(this);
    }

    @Override
    public String toString() {
        return "Ip{" +
                "id=" + id +
                '}';
    }
}
