package com.shata.migration.client;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shata.migration.connPool.ConnInstance;
import com.shata.migration.constants.Commands;
import com.shata.migration.exception.MigrationException;
import com.shata.migration.jdbc.JdbcManager;
import com.shata.migration.netty.Client;
import com.shata.migration.netty.pool.NettyInstance;
import com.shata.migration.utils.Config;
import com.shata.migration.utils.InetInfo;

public class MigrationTask implements Runnable {
	private final static Logger log = LoggerFactory.getLogger(MigrationTask.class);
	
	private String table;
	private String table_to;
	private String column_from;
	private String column_to;
	
	private String sql;
	private String insert_sql;
	private String select_sql;
	private String column_append;
	private boolean exist_all_content;
	
	private long min;
	private long max;
	private boolean fail;
	
	private Client conn = null;
	
	
	@Override
	public void run() {
		try {
			conn = NettyInstance.getInstance().getConnection();
		} catch (Exception e) {
			log.error("获取netty连接失败！", e);
			throw new MigrationException("获取netty连接失败！");
		}
		
		boolean loop = true;
		//1 注册设备
		reg_device();
		if(Commands.TABLE_TABLE.equals(table)) {
			log.info("所有表都迁移完成！");
			loop = false;
		}
		
		while(loop) {
			//2 获取迁移的id段
			get_segement();
			if(min == 0 && max == 0) {
				//当前表迁移完成，先注销当前连接，重新注册设备
				logout_device();
				reg_device();
				if(Commands.TABLE_TABLE.equals(table)) {
					log.info("所有表都迁移完成！");
					break;
				}
				continue;
			} else if(min == -2) {
				try {
					Thread.sleep(max * 1000);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				continue;
			}
			
			//3 迁移
			long start_time = System.currentTimeMillis();
			boolean flag = JdbcManager.migration(ConnInstance.getFromInstance()
					, ConnInstance.getToInstance(), sql, insert_sql, select_sql, column_append, fail, exist_all_content);
			log.info("迁移" + (flag ? "成功" : "失败") + ",花费时间:" + (System.currentTimeMillis() - start_time) 
					+ ",表名:" + table + ",id段:" + min + "-" + max);
			
			//4 状态更新
			update_status(flag ? Commands.STATUS_SUCC : Commands.STATUS_FAIL);
		}
		
		//归还连接
		try {
			NettyInstance.getInstance().releaseConnection(conn);
		} catch (Exception e) {
			log.error("归还netty连接失败！", e);
		}
	}
	
	/**
	 * 每个表都配置了相应的min,max属性，查看config.properties文件中的
	 * R_ALLHA_PERCENTAGE_minId=1
	 * R_ALLHA_PERCENTAGE_maxId=9999
	 * 即针对表R_ALLHA_PERCENTAGE迁移的数据区段
	 * @return
	 */
	public boolean get_segement() {
		String[] bodies = null;
		for(int i = 0; i < 10; i++) {
			try {
				bodies = (String[]) conn.invokeSync(Commands.GET_SEGEMENT + "|" + table + "|" + InetInfo.DEVICE_NAME + "|" + Thread.currentThread().getName());
				if(null != bodies && (bodies.length == 3 || bodies.length == 4) 
						&& !"-1".equals(bodies[1]) && !"-1".equals(bodies[2])) {
					break;
				}
			} catch (Exception e) {
				log.error("获取id段异常！" + table + "|" + InetInfo.DEVICE_NAME + "|" + Thread.currentThread().getName(), e);
			}
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		if(null == bodies || (bodies.length != 3 && bodies.length != 4) || "-1".equals(bodies[1]) || "-1".equals(bodies[2])) {
			throw new MigrationException("获取id段，重试10次还是失败！" + table + "|" + InetInfo.DEVICE_NAME + "|" + Thread.currentThread().getName());
		}
		
		min = Long.parseLong(bodies[1]);
		max = Long.parseLong(bodies[2]);
		if(bodies.length == 4) {
			fail = true;
		}
		
		sql = "select " + column_from + " from " + table + " where id>=" + min + " and id <=" + max;
		
		return true;
	}
	
	public boolean update_status(String status) {
		String[] bodies = null;
		for(int i = 0; i < 10; i++) {
			try {
				bodies = (String[]) conn.invokeSync(Commands.UPDATE_STATUS + "|" + table + "|" + min + "|" + max + "|" + status);
				if(null != bodies && bodies.length == 2) {
					break;
				}
			} catch (Exception e) {
				log.error("更新状态异常！" + table + "|" + min + "|" + max + "|" + status, e);
			}
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		if(null == bodies || bodies.length != 2) {
			log.error("更新状态，重试10次还是失败！" + table + "|" + min + "|" + max + "|" + status);
			return false;
			//更新状态 异常，不将线程中断， 逻辑上不影响程序的正确性
		}
		
		if(Commands.SUCC.equals(bodies[1])) {
			return true;
		}
		
		return false;
	}
	
	public boolean logout_device() {
		String[] bodies = null;
		for(int i = 0; i < 10; i++) {
			try {
				bodies = (String[]) conn.invokeSync(Commands.LOGOUT_DEVICE + "|" + InetInfo.DEVICE_NAME + "|" + Thread.currentThread().getName());
				if(null != bodies && bodies.length == 2) {
					break;
				}
			} catch (Exception e) {
				log.error("注销设备异常！" + InetInfo.DEVICE_NAME + "|" + Thread.currentThread().getName(), e);
			}
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		if(null == bodies || bodies.length != 2) {
			log.error("注销设备，重试10次还是失败！" + InetInfo.DEVICE_NAME + "|" + Thread.currentThread().getName());
			return false;
			//注销失败，不将 线程中断，因为会对 下线的设备进行能力值回收
			//throw new MigrationException("注销设备，重试10次还是失败！" + InetInfo.DEVICE_NAME + "|" + Thread.currentThread().getName());
		}
		
		if(Commands.SUCC.equals(bodies[1])) {
			return true;
		}
		
		return false;
	}
	
	public boolean reg_device() {
		String[] bodies = null;
		for(int i = 0; i < 10; i++) {
			try {
				bodies = (String[]) conn.invokeSync(Commands.REG_DEVICE + "|" + InetInfo.DEVICE_NAME + "|" + Thread.currentThread().getName() + "|" + Config.getSetting("ability"));
				if(null != bodies && bodies.length == 5) {
					break;
				}
			} catch (Exception e) {
				log.error("注册设备异常！" + InetInfo.DEVICE_NAME + "|" + Thread.currentThread().getName(), e);
			}
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		if(null == bodies || bodies.length != 5) {
			throw new MigrationException("注册设备，重试10次还是失败！" + InetInfo.DEVICE_NAME + "|" + Thread.currentThread().getName());
		}
		
		table = bodies[1];
		table_to = bodies[2];
		column_from = bodies[3];
		column_to = bodies[4];
		column_append = null;
		
		insert_sql = "insert into " + table_to + "(" + column_to + ") values(";
		if(StringUtils.isNotBlank(column_to) && StringUtils.isNotBlank(column_from)) {
			String[] columns = StringUtils.split(column_to, ",");
			String[] columns_from = StringUtils.split(column_from, ",");
			if(columns.length != columns_from.length && columns.length - columns_from.length != 1) {
				throw new MigrationException("不支持的表：" + table + ",column_to长度必须 等于 或 大于一个 column_from长度。columns_from=" 
						+ column_from + " columns_to=" + column_to);
			}
			if(columns.length - columns_from.length == 1) {
				column_append = columns[columns.length - 1];
				if(null == column_append || (!"sp_code".equals(column_append) && !"all_content".equals(column_append))) {
					throw new MigrationException("不支持的表：" + table + ",新增列仅支持 sp_code或all_content。columns_from=" 
							+ column_from + " columns_to=" + column_to);
				}
			}
			for(int i=0; i<columns.length; i++) {
				if(i != 0) {
					insert_sql += ",";
				}
				insert_sql += "?";
			}
		}
		insert_sql += ")";
		
		select_sql = "select id from " + table_to + " where 1=1 ";
		exist_all_content = false;
		if(StringUtils.isNotBlank(column_to)) {
			String[] columns = StringUtils.split(column_to, ",");
			for(int i=0; i<columns.length; i++) {
				if("all_content".equals(columns[i])) {
					exist_all_content = true;
					continue;
				}
				select_sql += " and " + columns[i] + "=? ";
			}
		}
		
		return true;
	}

}
