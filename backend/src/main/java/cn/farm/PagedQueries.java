package cn.farm;

import static cn.farm.Domain.*;
import static cn.farm.Store.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/** Read models for searchable, bounded UI lists. Export and offline preparation use their full-data contracts. */
@RestController
@RequestMapping("/api/query")
public class PagedQueries {
    final Store db; final Auth auth; final Ledger ledger; final Payroll payroll; final DailyReview daily; final PrintSnapshots prints;
    public PagedQueries(Store db,Auth auth,Ledger ledger,Payroll payroll,DailyReview daily,PrintSnapshots prints){this.db=db;this.auth=auth;this.ledger=ledger;this.payroll=payroll;this.daily=daily;this.prints=prints;}

    String required(Map<String,String> p,String key){String value=p.getOrDefault(key,"").trim();if(value.isEmpty())throw new Problem(400,"缺少查询条件："+key);return value;}
    int positive(Map<String,String> p,String key,int fallback){
        try{int n=Integer.parseInt(p.getOrDefault(key,""+fallback));if(n<1||key.equals("size")&&n>100||key.equals("page")&&n>1000000)throw new Exception();return n;}
        catch(Exception e){throw new Problem(400,"分页参数无效：页码须为正数，每页最多100条");}
    }
    String term(Map<String,String> p){String q=p.getOrDefault("q","").trim();if(q.length()>100)throw new Problem(400,"查询关键字最多100字");return q;}
    boolean matches(Object row,String q){return q.isBlank()||db.json.write(row).toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT));}
    Map<String,Object> envelope(List<?> items,long total,Map<String,String> p){return Map.of("items",items,"total",total,"page",positive(p,"page",1),"size",positive(p,"size",20));}
    Map<String,Object> slice(List<?> rows,Map<String,String> p){int size=positive(p,"size",20),page=positive(p,"page",1);long start=(long)(page-1)*size;return envelope(start>=rows.size()?List.of():rows.subList((int)start,(int)Math.min(start+size,rows.size())),rows.size(),p);}
    Map<String,Object> sql(String from,String where,String order,List<Object> args,Map<String,String> p){
        int size=positive(p,"size",20),page=positive(p,"page",1);
        Long total=db.jdbc.queryForObject("SELECT COUNT(*) "+from+where,Long.class,args.toArray());
        var values=new ArrayList<>(args);values.add(size);values.add((long)(page-1)*size);
        return envelope(db.rows("SELECT "+order.substring(0,order.indexOf('|'))+" "+from+where+" ORDER BY "+order.substring(order.indexOf('|')+1)+" LIMIT ? OFFSET ?",values.toArray()),total,p);
    }
    void keyword(StringBuilder where,List<Object> args,Map<String,String> p,String...columns){String q=term(p);if(q.isBlank())return;where.append(" AND (");for(int i=0;i<columns.length;i++){if(i>0)where.append(" OR ");where.append("LOWER(").append(columns[i]).append(") LIKE ? ESCAPE '!'");args.add("%"+q.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%");}where.append(")");}
    void equal(StringBuilder w,List<Object> a,Map<String,String> p,String key,String column){String value=p.getOrDefault(key,"");if(!value.isBlank()&&!value.equals("*")){w.append(" AND ").append(column).append("=?");a.add(value);}}

    @GetMapping("/catalog/{kind}") public Object catalog(@PathVariable String kind,@RequestParam Map<String,String> p,HttpServletRequest req){
        Actor actor=auth.actor(req);if(!actor.admin()&&!Set.of("baskets","grossWeights").contains(kind))throw new Problem(403,"只有老板可以查询该资料列表");
        String table=switch(kind){case "workers"->"worker";case "categories"->"category";case "products"->"product";case "groups"->"farm_group";case "plots"->"farm_plot";case "baskets"->"basket_type";case "grossWeights"->"gross_weight";case "users"->"farm_user";default->throw new Problem(404,"未知资料类型");};
        var w=new StringBuilder(" WHERE 1=1");var a=new ArrayList<Object>();
        keyword(w,a,p,kind.equals("users")?new String[]{"username","display_name"}:kind.equals("workers")?new String[]{"code","name","phone"}:new String[]{"name"});
        if(Set.of("true","false").contains(p.getOrDefault("active",""))){w.append(" AND active=?");a.add(Boolean.parseBoolean(p.get("active")));}
        if(kind.equals("workers"))equal(w,a,p,"groupId","group_id");if(kind.equals("products"))equal(w,a,p,"categoryId","category_id");
        String fields=kind.equals("users")?"id,username,display_name,role,active":"*";
        return sql("FROM "+table,w.toString(),fields+"|"+(kind.equals("workers")?"code":kind.equals("users")?"username":"name")+",id",a,p);
    }
    @GetMapping("/records") public Object records(@RequestParam Map<String,String> p,HttpServletRequest req){
        auth.actor(req);YearMonth month=YearMonth.parse(required(p,"month"));var a=new ArrayList<Object>(List.of(month.atDay(1),month.plusMonths(1).atDay(1)));
        var w=new StringBuilder(" WHERE r.work_date>=? AND r.work_date<?");
        if(!p.getOrDefault("deleted","false").equals("true"))w.append(" AND r.deleted=FALSE");
        keyword(w,a,p,"w.name","w.code","pr.name");equal(w,a,p,"period","r.period");equal(w,a,p,"productId","r.product_id");equal(w,a,p,"workerId","r.worker_id");
        for(String key:List.of("from","to"))if(!p.getOrDefault(key,"").isBlank()){w.append(" AND r.work_date").append(key.equals("from")?">=":"<=").append("?");a.add(LocalDate.parse(p.get(key)));}
        if(p.containsKey("groupId")&&!p.get("groupId").equals("*")){String group=p.get("groupId");w.append(group.isEmpty()?" AND (NOT EXISTS (SELECT 1 FROM record_group g WHERE g.record_id=r.id) OR EXISTS (SELECT 1 FROM record_group g WHERE g.record_id=r.id AND g.group_id=''))":" AND EXISTS (SELECT 1 FROM record_group g WHERE g.record_id=r.id AND g.group_id=?)");if(!group.isEmpty())a.add(group);}
        var result=new LinkedHashMap<>(sql("FROM harvest_record r JOIN worker w ON w.id=r.worker_id JOIN product pr ON pr.id=r.product_id",w.toString(),"r.id|r.work_date DESC,r.period,r.created_at DESC,r.id",a,p));
        @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)result.get("items");result.put("items",rows.stream().map(r->ledger.enrich(ledger.record(str(r,"id")))).toList());return result;
    }
    @GetMapping("/payroll") public Object wages(@RequestParam Map<String,String> p,HttpServletRequest req){
        auth.admin(req);var full=payroll.preview(required(p,"month"));
        @SuppressWarnings("unchecked") var workers=(List<Map<String,Object>>)full.get("workers");
        var filtered=workers.stream().filter(w->matches(List.of(w.get("code"),w.get("name")),term(p))).filter(w->{int sign=((BigDecimal)w.get("unpaid")).signum();return switch(p.getOrDefault("status","")){case "UNPAID"->sign>0;case "PAID"->sign==0;case "OVERPAID"->sign<0;default->true;};}).toList();
        var result=new LinkedHashMap<>(slice(filtered,p));BigDecimal confirmed=BigDecimal.ZERO,unpaid=BigDecimal.ZERO;
        for(var row:filtered){confirmed=confirmed.add((BigDecimal)row.get("confirmed"));unpaid=unpaid.add((BigDecimal)row.get("unpaid"));}result.put("summary",Map.of("confirmed",confirmed,"unpaid",unpaid));return result;
    }
    @GetMapping("/daily") public Object day(@RequestParam Map<String,String> p,HttpServletRequest req){
        Actor actor=auth.actor(req);
        @SuppressWarnings("unchecked") var full=(Map<String,Object>)daily.day(LocalDate.parse(required(p,"date")),actor);
        @SuppressWarnings("unchecked") var workers=(List<Map<String,Object>>)full.get("workers");
        var filtered=workers.stream().filter(w->matches(List.of(w.get("code"),w.get("name")),term(p))).filter(w->p.getOrDefault("status","ALL").equals("ALL")||p.get("status").equals(w.get("status"))).filter(w->{
            String group=p.getOrDefault("groupId","*");if(group.equals("*"))return true;
            @SuppressWarnings("unchecked") var records=(List<Map<String,Object>>)w.get("records");
            for(var record:records){@SuppressWarnings("unchecked") var groups=(List<Map<String,Object>>)record.get("groups");if(groups.isEmpty()&&group.isEmpty()||groups.stream().anyMatch(g->group.equals(g.get("group_id"))))return true;}return false;
        }).toList();return slice(filtered,p);
    }
    @GetMapping("/plot-costs") public Object costs(@RequestParam Map<String,String> p,HttpServletRequest req){
        auth.actor(req);var totals=new TreeMap<String,Map<String,Object>>();
        for(var r:ledger.list(auth.actor(req),required(p,"month"))){if(flag(r.get("deleted")))continue;if(r.containsKey("pricing_error"))throw new Problem(409,"存在计价异常，请先核对规则与数量");
            Rule rule=(Rule)r.get("rule");@SuppressWarnings("unchecked") var costs=(List<Map<String,Object>>)r.get("costs");
            if(costs==null||costs.isEmpty())costs=List.of(Map.of("plot_id","","plot_name","未指定地块","inputs_json",r.get("inputs_json"),"amount",((Quote)r.get("quote")).amount()));
            for(var c:costs){String key=str(c,"plot_id")+":"+str(r,"product_id");var row=totals.computeIfAbsent(key,k->{var v=new LinkedHashMap<String,Object>();v.put("plotId",c.get("plot_id"));v.put("plot",c.get("plot_name"));v.put("productId",r.get("product_id"));v.put("product",r.get("product_name"));v.put("kg",BigDecimal.ZERO);v.put("pieces",BigDecimal.ZERO);v.put("amount",BigDecimal.ZERO);v.put("weightOnly",true);v.put("pieceOnly",true);return v;});
                row.put("amount",((BigDecimal)row.get("amount")).add((BigDecimal)c.get("amount")));
                row.put("weightOnly",Boolean.TRUE.equals(row.get("weightOnly"))&&(rule.mode().equals("ALLOCATE")||rule.stages().stream().allMatch(s->s.type().equals("WEIGHT"))));
                row.put("pieceOnly",Boolean.TRUE.equals(row.get("pieceOnly"))&&rule.mode().equals("SEGMENTS")&&rule.stages().stream().allMatch(s->s.type().equals("PIECE")));
                Inputs input=db.json.read(str(c,"inputs_json"),Inputs.class);BigDecimal kg=(BigDecimal)row.get("kg"),pieces=(BigDecimal)row.get("pieces");
                if(rule.mode().equals("ALLOCATE"))kg=kg.add(input.totalWeight());else for(var stage:rule.stages()){BigDecimal value=input.quantities().getOrDefault(stage.key(),BigDecimal.ZERO);if(stage.type().equals("PIECE"))pieces=pieces.add(value);else kg=kg.add(input.weightUnit().equals("jin")?value.divide(new BigDecimal("2")):input.weightUnit().equals("g")?value.divide(new BigDecimal("1000")):value);}
                row.put("kg",kg);row.put("pieces",pieces);
            }
        }
        return slice(totals.values().stream().filter(r->matches(List.of(r.get("plot"),r.get("product")),term(p))).filter(r->p.getOrDefault("plotId","*").equals("*")||p.get("plotId").equals(r.get("plotId"))).filter(r->p.getOrDefault("productId","").isEmpty()||p.get("productId").equals(r.get("productId"))).toList(),p);
    }
    @GetMapping({"/audit","/payments","/settlements","/history","/settlement-lines","/prints"}) public Object history(@RequestParam Map<String,String> p,HttpServletRequest req){
        Actor actor=auth.actor(req);String kind=req.getRequestURI().substring(req.getRequestURI().lastIndexOf('/')+1);
        var w=new StringBuilder(" WHERE 1=1");var a=new ArrayList<Object>();String from,fields,order;
        switch(kind){
            case "audit","history"->{if(kind.equals("audit")&&!actor.admin())throw new Problem(403,"只有老板可以查看全量操作日志");from="FROM audit_log l JOIN farm_user u ON u.id=l.actor_id";fields="l.*,u.display_name AS actor_name";order="l.created_at DESC,l.id DESC";if(kind.equals("history")){if(!p.containsKey("recordId"))throw new Problem(400,"缺少记录编号");ledger.record(p.get("recordId"));equal(w,a,p,"recordId","l.entity_id");}keyword(w,a,p,"u.display_name","l.action","l.reason");equal(w,a,p,"action","l.action");}
            case "payments"->{if(!actor.admin())throw new Problem(403,"只有老板可以查看付款");from="FROM payment l JOIN worker u ON u.id=l.worker_id";fields="l.*,u.name AS worker_name";order="l.created_at DESC,l.id DESC";equal(w,a,p,"month","l.month_key");equal(w,a,p,"workerId","l.worker_id");equal(w,a,p,"method","l.method");keyword(w,a,p,"u.name","u.code","l.note","l.method");}
            case "settlements"->{if(!actor.admin())throw new Problem(403,"只有老板可以查看账目快照");from="FROM settlement l";fields="l.*";order="l.created_at DESC,l.id DESC";equal(w,a,p,"month","l.month_key");keyword(w,a,p,"l.id","l.kind");}
            case "settlement-lines"->{if(!actor.admin())throw new Problem(403,"只有老板可以查看账目快照");if(!p.containsKey("settlementId"))throw new Problem(400,"缺少快照编号");from="FROM settlement_line l JOIN worker u ON u.id=l.worker_id";fields="l.*,u.name AS worker_name";order="u.code,l.record_id,l.id";equal(w,a,p,"settlementId","l.settlement_id");keyword(w,a,p,"u.name","u.code");}
            default->{from="FROM daily_print_snapshot l JOIN farm_user u ON u.id=l.created_by";fields="l.*,u.display_name AS creator_name";order="l.created_at DESC,l.id DESC";equal(w,a,p,"date","l.work_date");keyword(w,a,p,"u.display_name","l.id");}
        }
        for(String key:List.of("from","to"))if(!p.getOrDefault(key,"").isBlank()){w.append(" AND l.created_at").append(key.equals("from")?">=":"<").append("?");a.add(key.equals("from")?LocalDate.parse(p.get(key)).atStartOfDay():LocalDate.parse(p.get(key)).plusDays(1).atStartOfDay());}
        return sql(from,w.toString(),fields+"|"+order,a,p);
    }
}
