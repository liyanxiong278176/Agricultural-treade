package cn.farm;

import static cn.farm.Domain.*;
import static cn.farm.Store.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class Payroll {
    final Store db;final Ledger ledger;
    public Payroll(Store db,Ledger ledger){this.db=db;this.ledger=ledger;}
    public Map<String,Object> preview(String month){
        YearMonth m=YearMonth.parse(month);var rows=db.rows("SELECT id FROM harvest_record WHERE work_date>=? AND work_date<? ORDER BY work_date,worker_id",m.atDay(1),m.plusMonths(1).atDay(1));
        List<Map<String,Object>> lines=new ArrayList<>();
        List<String> quotedRows=new ArrayList<>();List<Object> quotedArgs=new ArrayList<>();
        for(var r:rows){
            var record=ledger.enrich(ledger.record(str(r,"id")));if(record.containsKey("pricing_error"))throw new Problem(409,"存在无法计价记录："+record.get("worker_name")+" "+record.get("work_date")+" "+record.get("pricing_error"));
            BigDecimal amount=((Quote)record.get("quote")).amount();BigDecimal settled=db.jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM settlement_line WHERE record_id=?",BigDecimal.class,r.get("id"));BigDecimal delta=amount.subtract(settled);
            var line=new LinkedHashMap<String,Object>();line.put("record",record);line.put("amount",amount);line.put("settled",settled);line.put("delta",delta);lines.add(line);
            BigDecimal pieces=BigDecimal.ZERO,weight=BigDecimal.ZERO;
            if(!flag(record.get("deleted")))for(Part part:((Quote)record.get("quote")).parts()){if(part.type().equals("PIECE"))pieces=pieces.add(part.quantity());else weight=weight.add(part.quantity());}
            // Pricing normalizes each record (including allocation and per-record rounding).
            // A parameterized relation lets SQL aggregate these current quotes without
            // persisting a second balance that could become stale after a rule change.
            quotedRows.add("SELECT CAST(? AS CHAR(36)) AS record_id, CAST(? AS DECIMAL(30,6)) AS pieces, CAST(? AS DECIMAL(30,6)) AS weight_kg, CAST(? AS DECIMAL(30,2)) AS earned, CAST(? AS DECIMAL(30,2)) AS settled");
            Collections.addAll(quotedArgs,r.get("id"),pieces,weight,amount,settled);
        }
        List<Map<String,Object>> workers=new ArrayList<>();
        if(!quotedRows.isEmpty()){
            quotedArgs.add(m.atDay(1));quotedArgs.add(m.plusMonths(1).atDay(1));
            workers=db.rows("""
                SELECT r.worker_id, w.name, w.code,
                       SUM(q.pieces) AS pieces, SUM(q.weight_kg) AS weight_kg,
                       SUM(q.earned) AS earned, SUM(q.settled) AS settled
                FROM harvest_record r
                JOIN ("""+String.join(" UNION ALL ",quotedRows)+"""
                ) q ON q.record_id=r.id
                JOIN worker w ON w.id=r.worker_id
                WHERE r.work_date>=? AND r.work_date<?
                GROUP BY r.worker_id,w.name,w.code
                ORDER BY w.code,r.worker_id
                """,quotedArgs.toArray());
        }
        for(var w:workers){
            w.put("workerId",w.remove("worker_id"));w.put("weightKg",w.remove("weight_kg"));
            w.put("pending",((BigDecimal)w.get("earned")).subtract((BigDecimal)w.get("settled")));
            BigDecimal paid=db.jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM payment WHERE worker_id=? AND month_key=?",BigDecimal.class,w.get("workerId"),month);w.put("paid",paid);w.put("confirmed",w.get("earned"));w.put("unpaid",((BigDecimal)w.get("earned")).subtract(paid));
        }
        boolean closed=db.exists("SELECT month_key FROM ledger_month WHERE month_key=? AND closed=TRUE",month);
        return Map.of("month",month,"closed",closed,"workers",workers,"lines",lines,"settlements",db.rows("SELECT * FROM settlement WHERE month_key=? ORDER BY created_at DESC",month),"payments",db.rows("SELECT p.*,w.name AS worker_name,u.display_name AS actor_name FROM payment p JOIN worker w ON w.id=p.worker_id JOIN farm_user u ON u.id=p.created_by WHERE p.month_key=? ORDER BY p.created_at DESC",month));
    }
    @SuppressWarnings("unchecked")
    public Object settle(String month,Actor actor){
        YearMonth.parse(month);
        return db.tx.execute(status->{
            db.lockMonth(month);boolean closed=db.closed(month);var preview=preview(month);var lines=(List<Map<String,Object>>)preview.get("lines");
            var pending=lines.stream().filter(l->((BigDecimal)l.get("delta")).signum()!=0).toList();
            if(pending.isEmpty())throw new Problem(400,"没有待结算差额");
            String sid=id();db.jdbc.update("INSERT INTO settlement(id,month_key,kind,created_by) VALUES(?,?,?,?)",sid,month,closed?"SUPPLEMENT":"MONTHLY",actor.id());
            for(var l:pending){var r=(Map<String,Object>)l.get("record");db.jdbc.update("INSERT INTO settlement_line(id,settlement_id,record_id,worker_id,amount,snapshot_json) VALUES(?,?,?,?,?,?)",id(),sid,r.get("id"),r.get("worker_id"),l.get("delta"),db.json.write(l));}
            db.jdbc.update("UPDATE ledger_month SET closed=TRUE WHERE month_key=?",month);db.audit(actor,closed?"SUPPLEMENT":"SETTLE",sid,null,preview,"确认结算");return Map.of("id",sid,"kind",closed?"SUPPLEMENT":"MONTHLY");
        });
    }
    public Object pay(String paymentId,String worker,String month,BigDecimal amount,String method,String note,Actor actor){
        try{UUID.fromString(paymentId);UUID.fromString(worker);YearMonth.parse(month);}catch(Exception e){throw new Problem(400,"付款标识、工人或月份无效");}
        if(amount==null||amount.signum()<=0||amount.scale()>2)throw new Problem(400,"付款金额必须大于零且最多两位小数");
        if(method==null||!Set.of("现金","微信","支付宝","银行转账","其他").contains(method)||note==null||note.length()>500)throw new Problem(400,"付款方式或备注无效");
        return db.tx.execute(status->{
            db.lockMonth(month);var existing=db.rows("SELECT * FROM payment WHERE id=?",paymentId);if(!existing.isEmpty()){var p=existing.getFirst();if(!worker.equals(str(p,"worker_id"))||!month.equals(str(p,"month_key"))||amount.compareTo((BigDecimal)p.get("amount"))!=0||!method.equals(str(p,"method"))||!note.equals(str(p,"note")))throw new Problem(409,"付款标识已用于其他内容");return p;}
            var preview=preview(month);
            @SuppressWarnings("unchecked") var wages=(List<Map<String,Object>>)preview.get("workers");
            var wage=wages.stream().filter(w->worker.equals(w.get("workerId"))).findFirst().orElseThrow(()->new Problem(400,"该工人本月没有可付款工资"));
            BigDecimal due=(BigDecimal)wage.get("earned");
            BigDecimal paid=db.jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM payment WHERE worker_id=? AND month_key=?",BigDecimal.class,worker,month);
            if(amount.compareTo(due.subtract(paid))>0)throw new Problem(400,"付款超过当前未付工资");
            // Confirm only this worker. Receipt lookup, fresh quote, snapshot and payment share the month lock and transaction.
            @SuppressWarnings("unchecked") var lines=(List<Map<String,Object>>)preview.get("lines");
            var selected=lines.stream().filter(l->worker.equals(((Map<?,?>)l.get("record")).get("worker_id"))&&((BigDecimal)l.get("delta")).signum()!=0).toList();
            if(!selected.isEmpty()){
                String sid=id();boolean previous=db.exists("SELECT l.id FROM settlement_line l JOIN settlement s ON s.id=l.settlement_id WHERE l.worker_id=? AND s.month_key=?",worker,month);
                db.jdbc.update("INSERT INTO settlement(id,month_key,kind,created_by) VALUES(?,?,?,?)",sid,month,previous?"SUPPLEMENT":"MONTHLY",actor.id());
                for(var line:selected){var r=(Map<?,?>)line.get("record");db.jdbc.update("INSERT INTO settlement_line(id,settlement_id,record_id,worker_id,amount,snapshot_json) VALUES(?,?,?,?,?,?)",id(),sid,r.get("id"),worker,line.get("delta"),db.json.write(line));}
                db.jdbc.update("UPDATE ledger_month SET closed=TRUE WHERE month_key=?",month);
                db.audit(actor,"PAYROLL_SNAPSHOT",sid,null,selected,"登记付款时确认当前工人账目");
            }
            db.jdbc.update("INSERT INTO payment(id,worker_id,month_key,amount,method,note,created_by) VALUES(?,?,?,?,?,?,?)",paymentId,worker,month,amount,method,note,actor.id());var result=db.one("SELECT * FROM payment WHERE id=?",paymentId);db.audit(actor,"PAYMENT",paymentId,null,result,note);return result;
        });
    }
}
