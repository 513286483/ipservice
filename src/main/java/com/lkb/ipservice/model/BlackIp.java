package com.lkb.ipservice.model;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.springframework.jdbc.core.RowMapper;

public class BlackIp implements RowMapper<BlackIp>{

	private Integer id;
	private String host;
	private Integer port;
	private String source;

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

	public BlackIp() {
		super();
	}

	public BlackIp(Integer id, String host, Integer port, String source) {
		super();
		this.setId(id);
		this.setHost(host);
		this.setPort(port);
		this.source = source;
	}

	public String getSource() {
		return source;
	}

	public void setSource(String source) {
		this.source = source;
	}

	@Override
	public BlackIp mapRow(ResultSet rs, int rowNum) throws SQLException {
		return new BlackIp(rs.getInt("id"), rs.getString("host"), rs.getInt("port"), rs.getString("source"));
	}

}
