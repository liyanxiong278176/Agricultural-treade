package cn.farm;

import java.util.*;
import java.time.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import static cn.farm.Domain.*;

@Component
public class Store {
    public final JdbcTemplate jdbc; public final TransactionTemplate tx; public final Json json;
    public Store(JdbcTemplate jdbc,TransactionTemplate tx,Json json){this.jdbc=jdbc;this.tx=tx;this.json=json;this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);}
    public List<Map<String,Object>> rows(String sql,Object...args){return jdbc.query(sql,(rs,n)->{Map<String,Object> m=new LinkedHashMap<>();var meta=rs.getMetaData();for(int i=1;i<=meta.getColumnCount();i++){Object value=rs.getObject(i);if(value instanceof java.sql.Date d)value=d.toLocalDate().toString();if(value instanceof java.sql.Timestamp t)value=t.toLocalDateTime().toString();m.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT),value);}return m;},args);}
    public Map<String,Object> one(String sql,Object...args){var list=rows(sql,args);if(list.isEmpty())throw new Problem(404,"记录不存在");return list.getFirst();}
    public boolean exists(String sql,Object...args){return !rows(sql,args).isEmpty();}
    public static String id(){return UUID.randomUUID().toString();}
    public static boolean flag(Object v){return Boolean.TRUE.equals(v)||"1".equals(String.valueOf(v));}
    public static String str(Map<String,Object> m,String key){return Objects.toString(m.get(key),"");}
    public void audit(Actor actor,String action,String entity,Object before,Object after,String reason){jdbc.update("INSERT INTO audit_log(id,actor_id,action,entity_id,before_json,after_json,reason) VALUES(?,?,?,?,?,?,?)",id(),actor.id(),action,entity,json.write(before),json.write(after),reason==null?"":reason);}
    public void lockMonth(String month){
        // Insert in a separate short transaction before locking to avoid rolling back the month row on conflict.
        if(!exists("SELECT month_key FROM ledger_month WHERE month_key=?",month)){
            try{jdbc.update("INSERT INTO ledger_month(month_key,closed) VALUES(?,FALSE)",month);}catch(org.springframework.dao.DuplicateKeyException ignored){}
        }
        one("SELECT * FROM ledger_month WHERE month_key=? FOR UPDATE",month);
    }
    public boolean closed(String month){return flag(one("SELECT * FROM ledger_month WHERE month_key=?",month).get("closed"));}
}
