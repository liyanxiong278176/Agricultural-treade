package cn.farm;

import static cn.farm.Domain.*;
import static cn.farm.Store.*;
import java.time.*;
import java.util.*;
import java.math.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class Ledger {
    final Store db;final Catalog catalog;final Pricing pricing;final Workload workload;
    public Ledger(Store db,Catalog catalog,Pricing pricing,Workload workload){this.db=db;this.catalog=catalog;this.pricing=pricing;this.workload=workload;}
    LocalDate today(){return LocalDate.now(ZoneId.of("Asia/Shanghai"));}
    boolean settled(String recordId){return db.exists("SELECT id FROM settlement_line WHERE record_id=?",recordId);}
    public Map<String,Object> record(String id){var row=db.one("SELECT r.*,w.name AS worker_name,w.code AS worker_code,p.name AS product_name,u.display_name AS updated_name,c.display_name AS creator_name FROM harvest_record r JOIN worker w ON w.id=r.worker_id JOIN product p ON p.id=r.product_id JOIN farm_user u ON u.id=r.updated_by JOIN farm_user c ON c.id=r.created_by WHERE r.id=?",id);row.put("groups",workload.groups(id));row.put("allocations",workload.plots(id));return row;}
    public Map<String,Object> enrich(Map<String,Object> row){
        Map<String,Object> result=new LinkedHashMap<>(row);result.put("settled",settled(str(row,"id")));if(flag(row.get("deleted"))){result.put("quote",new Quote(BigDecimal.ZERO.setScale(2),List.of(),BigDecimal.ZERO));return result;}
        try{Rule rule=catalog.rule(str(row,"product_id"),LocalDate.parse(str(row,"work_date")));result.put("rule",rule);Quote quote=pricing.calculate(rule,db.json.read(str(row,"inputs_json"),Inputs.class));result.put("quote",quote);result.put("costs",workload.costs(str(row,"id"),rule,quote));}catch(Problem e){result.put("pricing_error",e.getMessage());}
        return result;
    }
    public List<Map<String,Object>> list(Actor a,String month){
        YearMonth m=YearMonth.parse(month);String scope="";
        List<Object> args=new ArrayList<>(List.of(m.atDay(1),m.plusMonths(1).atDay(1)));
        var rows=db.rows("SELECT r.id FROM harvest_record r WHERE r.work_date>=? AND r.work_date<?"+scope+" ORDER BY r.work_date DESC,r.period,r.created_at DESC",args.toArray());return rows.stream().map(r->enrich(record(str(r,"id")))).toList();
    }
    public Result apply(Operation op,Actor a){
        if(op==null||op.operationId()==null||op.recordId()==null)throw new Problem(400,"缺少操作标识");
        try{UUID.fromString(op.operationId());UUID.fromString(op.recordId());}catch(Exception e){throw new Problem(400,"操作标识格式不正确");}
        String hash=Auth.hash(db.json.write(op));
        try{return db.tx.execute(status->{
            db.jdbc.update("INSERT INTO operation_receipt(operation_id,actor_id,request_hash,result_json) VALUES(?,?,?,?)",op.operationId(),a.id(),hash,"{}");
            Result result=mutate(op,a);
            db.jdbc.update("UPDATE operation_receipt SET result_json=? WHERE operation_id=?",db.json.write(result),op.operationId());return result;
        });}catch(DuplicateKeyException e){
            var receipt=db.rows("SELECT * FROM operation_receipt WHERE operation_id=?",op.operationId());
            if(!receipt.isEmpty()){
                var r=receipt.getFirst();if(!a.id().equals(str(r,"actor_id"))||!hash.equals(str(r,"request_hash")))throw new Problem(409,"操作 ID 已用于其他内容，请勿复用");
                return db.json.read(str(r,"result_json"),Result.class);
            }
            throw new Problem(409,"同一工人、日期、时段和产品已有记录，请刷新后核对原数量");
        }
    }
    private Result mutate(Operation op,Actor a){
        String action=op.action();if(action==null||!Set.of("CREATE","UPDATE","DELETE","RESTORE").contains(action))throw new Problem(400,"未知操作");
        Map<String,Object> old=action.equals("CREATE")?null:record(op.recordId());
        String product=old==null?op.productId():str(old,"product_id");LocalDate date=old==null?op.workDate():LocalDate.parse(str(old,"work_date"));
        if(date==null||date.isAfter(today())||date.isBefore(LocalDate.of(2000,1,1)))throw new Problem(400,"请选择有效的实际采摘日期，不能录入未来日期");
        db.one("SELECT * FROM product WHERE id=? FOR UPDATE",product);
        String month=date.toString().substring(0,7);db.lockMonth(month);
        if(old!=null){
            old=record(op.recordId());
            boolean confirmed=settled(op.recordId());
            if(action.equals("DELETE")&&confirmed)throw new Problem(409,"该记录已纳入确认结算，不能直接删除，请通过更正数量处理，原账目快照保留");
            if(!action.equals("UPDATE")&&!a.admin()&&!date.equals(today())&&!(action.equals("DELETE")&&a.id().equals(str(old,"created_by")))&&!(action.equals("RESTORE")&&a.id().equals(str(old,"created_by"))))throw new Problem(403,"往日已有记录请联系老板更正，新增漏记请使用补录");
            if(action.equals("DELETE")&&!a.admin()&&!a.id().equals(str(old,"created_by")))throw new Problem(403,"只能删除自己录入的未结算记录");
            if(confirmed&&!a.admin()&&!action.equals("UPDATE"))throw new Problem(409,"该记录已纳入确认结算，请联系老板处理更正");
            if(op.version()==null||op.version()!=((Number)old.get("version")).intValue())return new Result("VERSION_CONFLICT","记录已被更新，请核对最新数量后再次提交",enrich(old));
            if(action.equals("UPDATE")&&flag(old.get("deleted")))throw new Problem(409,"该记录已删除，请先恢复");
            if(action.equals("UPDATE")&&(confirmed||date.isBefore(today()))&&(op.reason()==null||op.reason().isBlank()))throw new Problem(400,"历史或已有快照记录更正必须填写原因，应发与未付将更新");
        }
        String note=Objects.toString(op.note(),old==null?"":str(old,"note"));if(note.length()>1000)throw new Problem(400,"说明不能超过 1000 字");
        Inputs inputs=op.inputs();
        if(action.equals("CREATE")||action.equals("UPDATE")){
            if(date.isBefore(today())&&action.equals("CREATE")&&note.isBlank())throw new Problem(400,"往日补录必须填写说明");
            if(op.entry()==null){
                if(old!=null&&!workload.groups(op.recordId()).isEmpty())throw new Problem(400,"请通过增加采摘量或更正数量调整对应班组");
                if(inputs!=null&&inputs.weighings()!=null)inputs=workload.normalize(inputs,catalog.rule(product,date));
                pricing.calculate(catalog.rule(product,date),inputs);
            }
        }
        if(action.equals("CREATE")){
            if(!Set.of("AM","PM").contains(Objects.toString(op.period(),"")))throw new Problem(400,"请选择上午或下午");
            if(!db.exists("SELECT id FROM worker WHERE id=? AND active=TRUE",op.workerId())||!db.exists("SELECT p.id FROM product p JOIN category c ON c.id=p.category_id WHERE p.id=? AND p.active=TRUE AND c.active=TRUE",product))throw new Problem(400,"工人、产品或类别已停用，请联系老板核对");
            var duplicate=db.rows("SELECT id FROM harvest_record WHERE worker_id=? AND work_date=? AND period=? AND product_id=?",op.workerId(),date,op.period(),product);
            if(!duplicate.isEmpty()){var current=record(str(duplicate.getFirst(),"id"));boolean canRead=true;return new Result(flag(current.get("deleted"))?"DELETED_CONFLICT":"DUPLICATE_CONFLICT",flag(current.get("deleted"))?"已有删除记录，可恢复原记录":"已有相同时段记录，请核对后修改原数量",canRead?enrich(current):null);}
            if(op.entry()!=null){inputs=workload.apply(op,null,catalog.rule(product,date));pricing.calculate(catalog.rule(product,date),inputs);}
            db.jdbc.update("INSERT INTO harvest_record(id,worker_id,work_date,period,product_id,inputs_json,note,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?)",op.recordId(),op.workerId(),date,op.period(),product,db.json.write(inputs),note,a.id(),a.id());
        }else{
            if(action.equals("RESTORE")&&!flag(old.get("deleted")))return new Result("VERSION_CONFLICT","该记录已恢复，请核对",enrich(old));
            if(action.equals("UPDATE")&&op.entry()!=null){inputs=workload.apply(op,old,catalog.rule(product,date));pricing.calculate(catalog.rule(product,date),inputs);}
            boolean locationOnly=action.equals("UPDATE")&&op.entry()!=null&&op.entry().mode().equals("MOVE");
            String data=action.equals("UPDATE")&&!locationOnly?db.json.write(inputs):str(old,"inputs_json");boolean deleted=action.equals("DELETE");
            if(action.equals("RESTORE"))pricing.calculate(catalog.rule(product,date),db.json.read(data,Inputs.class));
            int changed=db.jdbc.update("UPDATE harvest_record SET inputs_json=?,note=?,deleted=?,updated_by=?,updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=? AND version=?",data,note,deleted,a.id(),op.recordId(),op.version());
            if(changed==0)return new Result("VERSION_CONFLICT","记录已被更新，请核对最新数量后再次提交",enrich(record(op.recordId())));
        }
        if(op.entry()==null&&(action.equals("CREATE")||action.equals("UPDATE"))){
            // Old queued payloads have no allocation metadata. Preserve their hash and assign their full quantity explicitly.
            Inputs normalized=workload.normalize(inputs,catalog.rule(product,date));
            db.jdbc.update("DELETE FROM record_plot WHERE record_id=?",op.recordId());
            db.jdbc.update("INSERT INTO record_plot VALUES(?,?,?,?,?,?)",op.recordId(),"","","未分组","未指定地块",db.json.write(normalized));
        }
        var result=record(op.recordId());String auditAction=op.entry()!=null&&action.equals("UPDATE")?op.entry().mode().equals("ADD")?"ADD_QUANTITY":op.entry().mode().equals("MOVE")?"MOVE_PLOT":"CORRECT_QUANTITY":action;
        Map<String,Object> after=new LinkedHashMap<>(result);if(op.entry()!=null)after.put("quantity_operation",op.entry());
        db.audit(a,auditAction,op.recordId(),old,after,op.reason());return new Result("ACCEPTED",db.closed(month)?"已保存，应发与未付按最新核算更新，历史账目快照保留":"后台已接收",enrich(result));
    }
}
