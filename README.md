# migration-tool
mysql数据迁移工具。

支持指定表名、列名，多线程+多进程。

保证高可用，数据一致性。

使用过程

1）创建数据库：migration，执行init.sql文件

2）修改config.properties属性值

3）启动Nettyserver

com.shata.migration.server.StartServer

4）启动迁移

com.shata.migration.client.StartMigration