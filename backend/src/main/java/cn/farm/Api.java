package cn.farm;

import static cn.farm.Domain.*;
import static cn.farm.Store.*;
import java.time.*;
import java.math.*;
import java.util.*;
import java.io.*;
import jakarta.servlet.http.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

@RestController
@RequestMapping("/api")
public class Api {
    final Auth auth;final Catalog catalog;final Ledger ledger;final Payroll payroll;final Pricing pricing;final Store db;final DailyReview daily;
    public Api(Auth auth,Catalog catalog,Ledger ledger,Payroll payroll,Pricing pricing,Store db,DailyReview daily){this.auth=auth;this.catalog=catalog;this.ledger=ledger;this.payroll=payroll;this.pricing=pricing;this.db=db;this.daily=daily;}
    record Login(String username,String password){} record Publish(String productId,LocalDate effectiveDate,Rule rule,String reason,String scope){} record Trial(Rule rule,Inputs inputs){} record Pay(String id,String workerId,String month,BigDecimal amount,String method,String note){}
    @GetMapping("/health") public Object health(){return Map.of("status","ok");}
    @PostMapping("/login") public Object login(@RequestBody Login body){return auth.login(body.username(),body.password());}
    @PostMapping("/logout") public Object logout(HttpServletRequest req){auth.actor(req);db.jdbc.update("DELETE FROM auth_session WHERE token_hash=?",Auth.hash(req.getHeader("Authorization").substring(7)));return Map.of("ok",true);}
    @GetMapping("/bootstrap") public Object bootstrap(HttpServletRequest req){return catalog.bootstrap(auth.actor(req));}
    @GetMapping("/users") public Object users(HttpServletRequest req){auth.admin(req);return db.rows("SELECT id,username,display_name,role,active FROM farm_user ORDER BY username");}
    @PostMapping("/catalog/{kind}") public Object save(@PathVariable String kind,@RequestBody Map<String,Object> body,HttpServletRequest req){Actor actor=auth.actor(req);if(!Set.of("baskets","grossWeights").contains(kind)&&!actor.admin())throw new Problem(403,"只有老板可以维护该资料");return catalog.save(kind,body,actor);}
    @PostMapping("/rules/trial") public Object trial(@RequestBody Trial body,HttpServletRequest req){auth.admin(req);return pricing.calculate(body.rule(),body.inputs());}
    @PostMapping("/rules") public Object publish(@RequestBody Publish body,HttpServletRequest req){Actor a=auth.admin(req);if(body.effectiveDate()==null||body.effectiveDate().isBefore(LocalDate.of(2000,1,1)))throw new Problem(400,"规则生效日期无效");return catalog.publish(body.productId(),body.effectiveDate(),body.rule(),body.reason(),body.scope()==null?"FROM_DATE":body.scope(),a);}
    @GetMapping("/records") public Object records(@RequestParam String month,HttpServletRequest req){return ledger.list(auth.actor(req),month);}
    @GetMapping("/records/{id}/history") public Object history(@PathVariable String id,HttpServletRequest req){Actor a=auth.actor(req);var r=ledger.record(id);return db.rows("SELECT l.*,u.display_name AS actor_name FROM audit_log l JOIN farm_user u ON u.id=l.actor_id WHERE entity_id=? ORDER BY created_at,id",id);}
    @GetMapping("/daily") public Object daily(@RequestParam LocalDate date,HttpServletRequest req){return daily.day(date,auth.actor(req));}
    @PostMapping("/daily/sign") public Object sign(@RequestBody DailyReview.Signature body,HttpServletRequest req){return daily.sign(body,auth.actor(req));}
    @PostMapping("/daily/template") public Object template(@RequestBody DailyReview.Template body,HttpServletRequest req){return daily.saveTemplate(body,auth.admin(req));}
    @PostMapping("/records/operate") public Object operate(@RequestBody Operation op,HttpServletRequest req){return ledger.apply(op,auth.actor(req));}
    @PostMapping("/sync") public Object sync(@RequestBody List<Operation> ops,HttpServletRequest req){
        Actor a=auth.actor(req);if(ops==null||ops.stream().anyMatch(Objects::isNull)||ops.size()>100)throw new Problem(400,"每批应为 0 至 100 项有效操作");
        List<Object> results=new ArrayList<>();for(Operation op:ops){try{results.add(Map.of("operationId",op.operationId(),"result",ledger.apply(op,a)));}catch(Problem e){results.add(Map.of("operationId",Objects.toString(op.operationId(),""),"result",Map.of("status","REJECTED","message",e.getMessage())));}}
        return results;
    }
    @GetMapping("/payroll") public Object payroll(@RequestParam String month,HttpServletRequest req){auth.admin(req);return payroll.preview(month);}
    @PostMapping("/payroll/{month}/confirm") public Object confirm(@PathVariable String month,HttpServletRequest req){return payroll.settle(month,auth.admin(req));}
    @PostMapping("/payments") public Object payment(@RequestBody Pay body,HttpServletRequest req){return payroll.pay(body.id(),body.workerId(),body.month(),body.amount(),body.method(),Objects.toString(body.note(),""),auth.admin(req));}
    @GetMapping("/audit") public Object audit(HttpServletRequest req){auth.admin(req);return db.rows("SELECT l.*,u.display_name AS actor_name FROM audit_log l JOIN farm_user u ON u.id=l.actor_id ORDER BY l.created_at DESC,l.id DESC LIMIT 500");}
    @GetMapping("/settlements/{id}/lines") public Object settlementLines(@PathVariable String id,HttpServletRequest req){auth.admin(req);return db.rows("SELECT l.*,w.name AS worker_name FROM settlement_line l JOIN worker w ON w.id=l.worker_id WHERE settlement_id=?",id);}
    @SuppressWarnings("unchecked")
    @GetMapping("/payroll/export") public ResponseEntity<byte[]> export(@RequestParam String month,@RequestParam Map<String,String> filters,HttpServletRequest req)throws IOException {
        auth.admin(req);Map<String,Object> data=payroll.preview(month);
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()){
            var sheet=book.createSheet("工资汇总");String[] headers={"工号","姓名","计件数量(筐)","计重数量(公斤)","当前核算","已确认应发","待补结算差额","已付","未付"};var head=sheet.createRow(0);for(int i=0;i<headers.length;i++)head.createCell(i).setCellValue(headers[i]);
            String search=filters.getOrDefault("q","").trim().toLowerCase(Locale.ROOT),state=filters.getOrDefault("status","");
            var selected=((List<Map<String,Object>>)data.get("workers")).stream().filter(w->(str(w,"name")+str(w,"code")).toLowerCase(Locale.ROOT).contains(search)).filter(w->{int sign=((BigDecimal)w.get("unpaid")).signum();return state.equals("UNPAID")?sign>0:state.equals("PAID")?sign==0:state.equals("OVERPAID")?sign<0:true;}).toList();
            var selectedWorkers=new HashSet<String>();selected.forEach(w->selectedWorkers.add(str(w,"workerId")));
            int n=1;for(var w:selected){var row=sheet.createRow(n++);String[] keys={"code","name","pieces","weightKg","earned","confirmed","pending","paid","unpaid"};for(int i=0;i<keys.length;i++){var cell=row.createCell(i);Object value=w.get(keys[i]);if(value instanceof BigDecimal b)cell.setCellValue(b.doubleValue());else cell.setCellValue(Objects.toString(value,""));}}
            for(int i=0;i<headers.length;i++)sheet.setColumnWidth(i,i<2?4500:6000);sheet.createFreezePane(0,1);
            var details=book.createSheet("已确认结算快照");String[] dh={"结算ID","类型","工人","原记录ID","本次确认金额","确认时间","快照"};var dr=details.createRow(0);for(int i=0;i<dh.length;i++)dr.createCell(i).setCellValue(dh[i]);
            var rows=db.rows("SELECT l.*,s.kind,s.created_at,w.name AS worker_name FROM settlement_line l JOIN settlement s ON s.id=l.settlement_id JOIN worker w ON w.id=l.worker_id WHERE s.month_key=? ORDER BY s.created_at",month);int ix=1;for(var l:rows){if(!selectedWorkers.contains(str(l,"worker_id")))continue;var row=details.createRow(ix++);String[] dk={"settlement_id","kind","worker_name","record_id","amount","created_at","snapshot_json"};for(int i=0;i<dk.length;i++)row.createCell(i).setCellValue(Objects.toString(l.get(dk[i]),""));}for(int i=0;i<dh.length;i++)details.setColumnWidth(i,6000);
            book.write(out);return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=payroll-"+month+".xlsx").contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(out.toByteArray());
        }
    }
}
