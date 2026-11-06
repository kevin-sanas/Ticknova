package com.ticknova.workspace;
import com.ticknova.identity.AuthService;
import com.ticknova.shared.Actor;
import com.ticknova.shared.ApiException;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.security.crypto.password.PasswordEncoder;

class AnalyticsEventsFlowTest {
 static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 static void until(java.util.function.BooleanSupplier condition,String name)throws Exception {long limit=System.currentTimeMillis()+5000;while(!condition.getAsBoolean()&&System.currentTimeMillis()<limit)Thread.sleep(10);require(condition.getAsBoolean(),name);}
 static Object proxy(Class<?> type,InvocationHandler fn){return Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},fn);}
 static Object zero(Class<?> type){if(!type.isPrimitive())return null;if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;return null;}
 static class Auth extends AuthService {
  volatile boolean admin=true,valid=true; final AtomicInteger calls=new AtomicInteger();
  Auth(){super(null,null,new PasswordEncoder(){public String encode(CharSequence raw){return "unused";}public boolean matches(CharSequence raw,String encoded){return false;}},null);}
  @Override public Actor authenticate(String token){calls.incrementAndGet();if(!valid)throw new ApiException(401,"UNAUTHENTICATED","expired");return new Actor(UUID.fromString("10000000-0000-0000-0000-000000000001"),"x","x",admin?Set.of("ADMIN"):Set.of("REQUESTER"),false);}
 }
 @org.junit.jupiter.api.Test void authorizationDeliveryAndDisconnect() throws Exception{
  var fail=new AtomicBoolean(false);var notice=new AtomicBoolean(false);var unlisten=new AtomicInteger();
  PGNotification notification=(PGNotification)proxy(PGNotification.class,(p,m,a)->zero(m.getReturnType()));
  PGConnection pg=(PGConnection)proxy(PGConnection.class,(p,m,a)->{if(m.getName().equals("getNotifications")){Thread.sleep(10);if(fail.get())throw new SQLException("simulated disconnect");return notice.getAndSet(false)?new PGNotification[]{notification}:null;}return zero(m.getReturnType());});
  Statement statement=(Statement)proxy(Statement.class,(p,m,a)->{if(m.getName().equals("execute")&&((String)a[0]).startsWith("UNLISTEN"))unlisten.incrementAndGet();return zero(m.getReturnType());});
  Connection conn=(Connection)proxy(Connection.class,(p,m,a)->switch(m.getName()){case "createStatement" -> statement;case "unwrap" -> pg;default -> zero(m.getReturnType());});
  DataSource source=(DataSource)proxy(DataSource.class,(p,m,a)->{if(m.getName().equals("getConnection")){if(fail.get())throw new SQLException("down");return conn;}return zero(m.getReturnType());});
  var auth=new Auth();var events=new AnalyticsEvents(source,auth);
  try {
  auth.admin=false;try{events.subscribe("token");throw new AssertionError("non-admin accepted");}catch(ApiException e){require(e.status()==403,"403");}
  auth.admin=true;try{events.subscribe("token");throw new AssertionError("offline accepted");}catch(ApiException e){require(e.status()==503,"503");}
  events.start();
  Field field=AnalyticsEvents.class.getDeclaredField("connected");field.setAccessible(true);
  until(()->{try{return field.getBoolean(events);}catch(Exception e){return false;}},"listener connects");
  int beforeOpen=auth.calls.get();events.subscribe("token");
  until(()->auth.calls.get()>=beforeOpen+2,"initial signal reauthorizes");
  int start=auth.calls.get();notice.set(true);until(()->auth.calls.get()>start,"notification reauthorizes and sends");
  Field subscriptions=AnalyticsEvents.class.getDeclaredField("subscribers");subscriptions.setAccessible(true);
  java.util.Map<?,?> map=(java.util.Map<?,?>)subscriptions.get(events);
  auth.admin=false;events.heartbeat();until(()->map.isEmpty(),"role revocation closes subscription");
  auth.admin=true;events.subscribe("token");fail.set(true);until(()->map.isEmpty(),"listener disconnect closes subscription");
  until(()->unlisten.get()>0,"listener unlistens before pool return");
  } finally { events.close(); }

 }
}
