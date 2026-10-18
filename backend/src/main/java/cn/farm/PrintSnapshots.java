package cn.farm;

import static cn.farm.Domain.*;
import static cn.farm.Store.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class PrintSnapshots {
    final Store db;final DailyReview daily;
    public PrintSnapshots(Store db,DailyReview daily){this.db=db;this.daily=daily;}
    public record Capture(String id,LocalDate date,String groupId,Map<String,String> fingerprints) {}
    @SuppressWarnings("unchecked")
    public Object capture(Capture request,Actor actor){
        if(request==null||request.date()==null||request.fingerprints()==null||request.fingerprints().isEmpty())throw new Problem(400,"没有可打印的核对记录");
        try{UUID.fromString(request.id());}catch(Exception e){throw new Problem(400,"打印版本标识无效");}
        return db.tx.execute(tx->{
            db.lockMonth(request.date().toString().substring(0,7));
            var existing=db.rows("SELECT * FROM daily_print_snapshot WHERE id=?",request.id());
            if(!existing.isEmpty()){
                var row=existing.getFirst();Map<String,Object> data=db.json.read(str(row,"snapshot_json"),Map.class);
                if(!actor.id().equals(str(row,"created_by"))||!request.date().toString().equals(str(row,"work_date"))||!Objects.equals(request.groupId(),str(row,"group_id"))||!request.fingerprints().equals(data.get("fingerprints")))throw new Problem(409,"打印编号已用于其他内容");
                return data;
            }
            Map<String,Object> day=(Map<String,Object>)daily.day(request.date(),actor);
            var selected=((List<Map<String,Object>>)day.get("workers")).stream().filter(w->request.fingerprints().containsKey(str(w,"workerId"))).toList();
            if(selected.size()!=request.fingerprints().size()||selected.stream().anyMatch(w->!Boolean.TRUE.equals(w.get("valid"))||!request.fingerprints().get(str(w,"workerId")).equals(w.get("fingerprint"))))throw new Problem(409,"账目已变化，请刷新后重新打印");
            var data=new LinkedHashMap<>(day);data.put("workers",selected);data.put("id",request.id());data.put("fingerprints",request.fingerprints());data.put("groupId",Objects.toString(request.groupId(),"*"));data.put("generatedAt",LocalDateTime.now(ZoneId.of("Asia/Shanghai")).toString());data.put("generatedBy",actor.name());
            var entities=new HashSet<String>();for(var w:selected)for(var r:(List<Map<String,Object>>)w.get("records")){entities.add(str(r,"id"));entities.add(str(r,"product_id"));}
            data.put("baselineAuditIds",db.rows("SELECT id,entity_id FROM audit_log").stream().filter(l->entities.contains(str(l,"entity_id"))).map(l->str(l,"id")).toList());
            db.jdbc.update("INSERT INTO daily_print_snapshot(id,work_date,group_id,snapshot_json,created_by) VALUES(?,?,?,?,?)",request.id(),request.date(),Objects.toString(request.groupId(),"*"),db.json.write(data),actor.id());
            db.audit(actor,"PRINT_SNAPSHOT",request.id(),null,Map.of("date",request.date(),"workers",selected.size(),"version",request.id()),"生成打印版本，不代表已打印或签字");return data;
        });
    }
    public Object list(LocalDate date){return db.rows("SELECT s.id,s.work_date,s.group_id,s.created_at,u.display_name AS creator_name FROM daily_print_snapshot s JOIN farm_user u ON u.id=s.created_by WHERE s.work_date=? ORDER BY s.created_at DESC,s.id",date);}
    @SuppressWarnings("unchecked")
    public Object compare(String id,Actor actor){
        var row=db.one("SELECT * FROM daily_print_snapshot WHERE id=?",id);
        return db.tx.execute(tx->{
            Map<String,Object> old=db.json.read(str(row,"snapshot_json"),Map.class);
            Map<String,Object> current=(Map<String,Object>)daily.day(LocalDate.parse(str(row,"work_date")),actor);
            var baseVersions=new HashMap<String,Integer>();var workerIds=new HashSet<String>();var recordIds=new HashSet<String>();var productIds=new HashSet<String>();
            for(var w:(List<Map<String,Object>>)old.get("workers")){workerIds.add(str(w,"workerId"));for(var r:(List<Map<String,Object>>)w.get("records")){recordIds.add(str(r,"id"));productIds.add(str(r,"product_id"));baseVersions.put(str(r,"id"),((Number)r.get("version")).intValue());}}
            var selected=((List<Map<String,Object>>)current.get("workers")).stream().filter(w->workerIds.contains(str(w,"workerId"))).toList();
            for(var w:selected)for(var r:(List<Map<String,Object>>)w.get("records")){recordIds.add(str(r,"id"));productIds.add(str(r,"product_id"));baseVersions.putIfAbsent(str(r,"id"),0);}
            var copy=new LinkedHashMap<>(current);copy.put("workers",selected);
            var baseline=new HashSet<>((List<String>)old.getOrDefault("baselineAuditIds",List.of()));
            var ids=new HashSet<>(recordIds);ids.addAll(productIds);var logs=new ArrayList<Map<String,Object>>();
            for(var log:db.rows("SELECT l.*,u.display_name AS actor_name FROM audit_log l JOIN farm_user u ON u.id=l.actor_id WHERE l.created_at>=? ORDER BY l.created_at,l.id",row.get("created_at")))if(ids.contains(str(log,"entity_id"))){
                Map<String,Object> after=db.json.read(str(log,"after_json"),Map.class);
                if(after==null||baseline.contains(str(log,"id")))continue;
                String entity=str(log,"entity_id");
                if(recordIds.contains(entity)&&after.get("version") instanceof Number v&&v.intValue()>baseVersions.get(entity))logs.add(log);
                else if(productIds.contains(entity)&&str(log,"action").equals("PUBLISH_RULE"))logs.add(log);
            }
            return Map.of("snapshot",old,"current",copy,"changes",logs);
        });
    }
}
