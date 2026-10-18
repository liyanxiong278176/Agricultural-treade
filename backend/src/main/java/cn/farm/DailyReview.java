package cn.farm;

import static cn.farm.Domain.*;
import static cn.farm.Store.*;
import java.time.*;
import java.math.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class DailyReview {
    final Store db;final Ledger ledger;
    public DailyReview(Store db,Ledger ledger){this.db=db;this.ledger=ledger;}
    public record Template(String title,List<String> columns,Integer fontSize,Integer signatureWidth) {}
    public record Signature(String workerId,LocalDate date,String fingerprint) {}
    static final List<String> COLUMNS=List.of("code","name","groups","am","pm","amWage","pmWage","total","signature");
    Template defaults(){return new Template("每日采摘核对表",COLUMNS,12,130);}
    public Template template(){var rows=db.rows("SELECT template_json FROM print_template WHERE id='daily'");return rows.isEmpty()?defaults():db.json.read(str(rows.getFirst(),"template_json"),Template.class);}
    public Template saveTemplate(Template t,Actor a){
        if(!a.admin())throw new Problem(403,"只有老板可以修改打印模板");
        if(t==null||t.title()==null||t.title().isBlank()||t.title().length()>80||t.columns()==null||!COLUMNS.containsAll(t.columns())||new HashSet<>(t.columns()).size()!=t.columns().size()||!t.columns().containsAll(List.of("name","signature"))||t.fontSize()==null||t.fontSize()<9||t.fontSize()>24||t.signatureWidth()==null||t.signatureWidth()<80||t.signatureWidth()>300)throw new Problem(400,"模板需保留姓名和签字栏，字号 9–24，签字栏宽度 80–300");
        return db.tx.execute(s->{Object before=template();if(db.exists("SELECT id FROM print_template WHERE id='daily'"))db.jdbc.update("UPDATE print_template SET template_json=? WHERE id='daily'",db.json.write(t));else db.jdbc.update("INSERT INTO print_template(id,template_json) VALUES('daily',?)",db.json.write(t));db.audit(a,"PRINT_TEMPLATE","daily",before,t,"");return t;});
    }
    void checkDate(LocalDate date){if(date==null||date.isAfter(ledger.today())||date.isBefore(LocalDate.of(2000,1,1)))throw new Problem(400,"请选择有效核对日期");}
    List<Map<String,Object>> all(LocalDate date){return db.rows("SELECT id FROM harvest_record WHERE work_date=? ORDER BY worker_id,period,product_id,id",date).stream().map(r->ledger.enrich(ledger.record(str(r,"id")))).toList();}
    boolean readable(List<Map<String,Object>> rows,Actor a,LocalDate date){return true;}
    String fingerprint(List<Map<String,Object>> rows){
        // Signing confirms printed work and wages; settlement status or an editor's name is not a change of work.
        var content=rows.stream().map(r->{var row=new LinkedHashMap<String,Object>();for(String key:List.of("id","worker_id","worker_name","worker_code","work_date","period","product_id","product_name","inputs_json","deleted","groups","quote","pricing_error","rule"))if(r.containsKey(key))row.put(key,r.get(key));return row;}).toList();
        return Auth.hash(db.json.write(content));
    }
    public Object day(LocalDate date,Actor a){
        checkDate(date);return db.tx.execute(s->{db.lockMonth(date.toString().substring(0,7));return view(date,a);});
    }
    private Object view(LocalDate date,Actor a){
        var grouped=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(var r:all(date))grouped.computeIfAbsent(str(r,"worker_id"),k->new ArrayList<>()).add(r);
        List<Object> workers=new ArrayList<>();
        for(var entry:grouped.entrySet()){
            var rows=entry.getValue();if(!readable(rows,a,date))continue;
            var active=rows.stream().filter(r->!flag(r.get("deleted"))).toList();if(active.isEmpty())continue;
            String fingerprint=fingerprint(rows);var signed=db.rows("SELECT s.*,u.display_name AS signer_name FROM daily_signature s JOIN farm_user u ON u.id=s.signed_by WHERE worker_id=? AND work_date=?",entry.getKey(),date);
            var w=new LinkedHashMap<String,Object>();var first=active.getFirst();w.put("workerId",entry.getKey());w.put("code",first.get("worker_code"));w.put("name",first.get("worker_name"));w.put("records",active);w.put("fingerprint",fingerprint);
            w.put("status",signed.isEmpty()?"UNSIGNED":fingerprint.equals(str(signed.getFirst(),"fingerprint"))?"SIGNED":"RESIGN");w.put("signature",signed.isEmpty()?null:signed.getFirst());
            BigDecimal am=BigDecimal.ZERO,pm=BigDecimal.ZERO;boolean valid=true;
            for(var r:active){if(r.get("quote") instanceof Quote q){if(str(r,"period").equals("AM"))am=am.add(q.amount());else pm=pm.add(q.amount());}else valid=false;}
            w.put("amWage",am);w.put("pmWage",pm);w.put("total",am.add(pm));w.put("valid",valid);workers.add(w);
        }
        return Map.of("date",date.toString(),"workers",workers,"template",template());
    }
    public Object sign(Signature request,Actor a){
        if(request==null||request.workerId()==null||request.fingerprint()==null)throw new Problem(400,"缺少签字信息");checkDate(request.date());
        return db.tx.execute(s->{
            db.lockMonth(request.date().toString().substring(0,7));
            var rows=all(request.date()).stream().filter(r->request.workerId().equals(str(r,"worker_id"))).toList();
            if(rows.isEmpty()||rows.stream().allMatch(r->flag(r.get("deleted"))))throw new Problem(400,"当天没有可核对记录");
            if(!readable(rows,a,request.date()))throw new Problem(403,"无权确认该工人当天的完整账目");
            if(rows.stream().anyMatch(r->!flag(r.get("deleted"))&&r.containsKey("pricing_error")))throw new Problem(400,"请先解决计价异常");
            String fingerprint=fingerprint(rows);if(!fingerprint.equals(request.fingerprint()))throw new Problem(409,"核对内容已变化，请刷新、重新打印并核对签字");
            var before=db.rows("SELECT * FROM daily_signature WHERE worker_id=? AND work_date=?",request.workerId(),request.date());
            if(!before.isEmpty()&&fingerprint.equals(str(before.getFirst(),"fingerprint")))return Map.of("ok",true);
            if(before.isEmpty())db.jdbc.update("INSERT INTO daily_signature(worker_id,work_date,fingerprint,snapshot_json,signed_by) VALUES(?,?,?,?,?)",request.workerId(),request.date(),fingerprint,db.json.write(rows),a.id());
            else db.jdbc.update("UPDATE daily_signature SET fingerprint=?,snapshot_json=?,signed_by=?,signed_at=CURRENT_TIMESTAMP WHERE worker_id=? AND work_date=?",fingerprint,db.json.write(rows),a.id(),request.workerId(),request.date());
            db.audit(a,"DAILY_SIGN",request.workerId(),before,Map.of("date",request.date(),"fingerprint",fingerprint,"records",rows),"已收回纸质签字表，仅核对账目，不代表付款");return Map.of("ok",true);
        });
    }
}
