package com.sky.service;

import com.sky.entity.Orders;
import com.sky.mapper.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import java.io.InputStream;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实执行 MyBatis SQL，但仅使用隔离 H2 内存库，不接触开发 MySQL。 */
class DatabaseConcurrencyTest {
    JdbcDataSource dataSource; SqlSessionFactory factory;
    @BeforeEach void setup() throws Exception {
        dataSource=new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:sky"+System.nanoTime()+";MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER;LOCK_TIMEOUT=5000");
        try(Connection c=dataSource.getConnection();Statement s=c.createStatement()) {
            s.execute("create table user(id bigint primary key)");
            s.execute("insert into user values(7)");
            s.execute("create table shopping_cart(id bigint primary key,user_id bigint,number int)");
            s.execute("insert into shopping_cart values(1,7,1)");
            s.execute("create table orders(id bigint primary key,user_id bigint,status int,pay_status int,order_time timestamp,checkout_time timestamp,pay_method int,cancel_time timestamp,cancel_reason varchar(100),rejection_reason varchar(100),delivery_time timestamp)");
            s.execute("insert into orders(id,user_id,status,pay_status,order_time) values(1,7,1,0,CURRENT_TIMESTAMP)");
        }
        Configuration config=new Configuration(new Environment("test",new JdbcTransactionFactory(),dataSource));
        config.setMapUnderscoreToCamelCase(true);config.getTypeAliasRegistry().registerAliases("com.sky.entity");
        config.addMapper(UserMapper.class);
        try(InputStream stream=getClass().getResourceAsStream("/mapper/OrderMapper.xml")) {
            new XMLMapperBuilder(stream,config,"mapper/OrderMapper.xml",config.getSqlFragments()).parse();
        }
        factory=new SqlSessionFactoryBuilder().build(config);
    }
    @Test void paymentAndCancellationOnlyOneCanWin() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Future<Integer> pay=pool.submit(()->{try(SqlSession session=factory.openSession()){ready.countDown();start.await();int n=session.getMapper(OrderMapper.class).payIfPending(1L,7L,LocalDateTime.now());session.commit();return n;}});
            Future<Integer> cancel=pool.submit(()->{try(SqlSession session=factory.openSession()){ready.countDown();start.await();int n=session.getMapper(OrderMapper.class).updateExpected(Orders.builder().id(1L).status(6).cancelReason("timeout").build(),1);session.commit();return n;}});
            assertTrue(ready.await(3,TimeUnit.SECONDS));start.countDown();assertEquals(1,pay.get(5,TimeUnit.SECONDS)+cancel.get(5,TimeUnit.SECONDS));
            try(SqlSession session=factory.openSession()){Orders row=session.getMapper(OrderMapper.class).getById(1L);assertTrue(row.getStatus()==2 || row.getStatus()==6);assertEquals(row.getStatus()==2 ? 1 : 0,row.getPayStatus());}
        } finally {start.countDown();pool.shutdownNow();}
    }
    @Test void userLockSerializesCartConsumptionAcrossTransactions() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2);CountDownLatch start=new CountDownLatch(1);
        Callable<Integer> consume=()->{try(SqlSession session=factory.openSession()){ready.countDown();start.await();session.getMapper(UserMapper.class).lockById(7L);int count;try(Statement s=session.getConnection().createStatement();ResultSet r=s.executeQuery("select count(*) from shopping_cart where user_id=7")){r.next();count=r.getInt(1);}try(Statement s=session.getConnection().createStatement()){s.executeUpdate("delete from shopping_cart where user_id=7");}session.commit();return count;}};
        try {Future<Integer>a=pool.submit(consume);Future<Integer>b=pool.submit(consume);assertTrue(ready.await(3,TimeUnit.SECONDS));start.countDown();assertEquals(1,a.get(5,TimeUnit.SECONDS)+b.get(5,TimeUnit.SECONDS));}
        finally {start.countDown();pool.shutdownNow();}
    }
    @Test void latePaymentAndWrongOwnerUpdateZeroRows() {
        try(SqlSession session=factory.openSession()) {
            OrderMapper mapper=session.getMapper(OrderMapper.class);
            assertEquals(0,mapper.payIfPending(1L,8L,LocalDateTime.now()));
            assertEquals(0,mapper.payIfPending(1L,7L,LocalDateTime.now().plusMinutes(16)));
        }
    }
}
