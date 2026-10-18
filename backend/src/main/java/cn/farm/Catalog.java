package cn.farm;

import static cn.farm.Domain.*;
import static cn.farm.Store.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class Catalog {
    final Store db;final Pricing pricing;
    public Catalog(Store db,Pricing pricing){this.db=db;this.pricing=pricing;}
    public Rule rule(String product,LocalDate date){
        var rows=db.rows("SELECT * FROM price_rule WHERE product_id=? AND effective_date<=? AND (end_date IS NULL OR end_date>=?) ORDER BY revision DESC",product,date,date);
        if(rows.isEmpty())throw new Problem(400,"该产品在所选采摘日期没有可用计费规则，请联系老板配置");
        return db.json.read(str(rows.getFirst(),"rule_json"),Rule.class);
    }
    public List<Map<String,Object>> rules(){return db.rows("SELECT r.*,u.display_name AS creator_name FROM price_rule r JOIN farm_user u ON u.id=r.created_by ORDER BY effective_date DESC,revision DESC");}
    public Map<String,Object> bootstrap(Actor a){
        var workers=db.rows("SELECT * FROM worker ORDER BY code");var products=db.rows("SELECT * FROM product ORDER BY name");
        return Map.of("grossWeights",db.rows("SELECT * FROM gross_weight ORDER BY name"),"workers",workers,"categories",db.rows("SELECT * FROM category ORDER BY name"),"products",products,"rules",rules(),"groups",db.rows("SELECT * FROM farm_group ORDER BY name"),"baskets",db.rows("SELECT * FROM basket_type ORDER BY name"),"plots",db.rows("SELECT * FROM farm_plot ORDER BY name"),"today",LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(),"user",a);
    }
    public Object save(String kind,Map<String,Object> body,Actor a){
        if(!Set.of("workers","categories","products","users","groups","baskets","plots","grossWeights").contains(kind))throw new Problem(404,"未知资料类型");
        String entity=Objects.toString(body.get("id"),id()),name=Objects.toString(body.get("name"),"").trim();
        if(name.isBlank()||name.length()>80)throw new Problem(400,"名称应为 1 至 80 个字符");
        boolean active=!Boolean.FALSE.equals(body.get("active"));
        return db.tx.execute(status->{
            String table=switch(kind){case "workers"->"worker";case "categories"->"category";case "products"->"product";case "groups"->"farm_group";case "baskets"->"basket_type";case "grossWeights"->"gross_weight";case "plots"->"farm_plot";default->"farm_user";};
            Object before=db.rows("SELECT "+(kind.equals("users")?"id,username,display_name,role,active":"*")+" FROM "+table+" WHERE id=?",entity);
            boolean present=db.exists("SELECT id FROM "+table+" WHERE id=?",entity);
            switch(kind){
                case "workers"->{String code=Objects.toString(body.get("code"),"").trim(),phone=Objects.toString(body.get("phone"),"");if(code.isBlank()||code.length()>40||phone.length()>40)throw new Problem(400,"请填写有效工号和联系方式");
                    String previousGroup=present?str(db.one("SELECT group_id FROM worker WHERE id=?",entity),"group_id"):"";
                    String group=Objects.toString(body.get("groupId"),Objects.toString(body.get("group_id"),previousGroup));
                    if(!group.isBlank()&&!group.equals(previousGroup)&&!db.exists("SELECT id FROM farm_group WHERE id=? AND active=TRUE",group))throw new Problem(400,"请选择启用的班组");
                    if(present)db.jdbc.update("UPDATE worker SET code=?,name=?,phone=?,active=?,group_id=? WHERE id=?",code,name,phone,active,group,entity);else db.jdbc.update("INSERT INTO worker(id,code,name,phone,active,group_id) VALUES(?,?,?,?,?,?)",entity,code,name,phone,active,group);}
                case "groups"->{if(present)db.jdbc.update("UPDATE farm_group SET name=?,active=? WHERE id=?",name,active,entity);else db.jdbc.update("INSERT INTO farm_group(id,name,active) VALUES(?,?,?)",entity,name,active);}
                case "plots"->{if(present)db.jdbc.update("UPDATE farm_plot SET name=?,active=? WHERE id=?",name,active,entity);else db.jdbc.update("INSERT INTO farm_plot(id,name,active) VALUES(?,?,?)",entity,name,active);}
                case "baskets","grossWeights"->{
                    java.math.BigDecimal weight;
                    try{weight=new java.math.BigDecimal(Objects.toString(body.get("weightKg"),""));}catch(Exception e){throw new Problem(400,"请填写配置重量（公斤）");}
                    if(weight.signum()<=0||weight.scale()>6||weight.compareTo(new java.math.BigDecimal("10000"))>0)throw new Problem(400,"重量应大于零、最多六位小数且不超过一万公斤");
                    int revision=present?((Number)db.one("SELECT revision FROM "+table+" WHERE id=? FOR UPDATE",entity).get("revision")).intValue()+1:1;
                    if(present)db.jdbc.update("UPDATE "+table+" SET name=?,weight_kg=?,active=?,revision=? WHERE id=?",name,weight,active,revision,entity);
                    else db.jdbc.update("INSERT INTO "+table+"(id,name,weight_kg,active,revision) VALUES(?,?,?,?,?)",entity,name,weight,active,revision);
                    db.jdbc.update("INSERT INTO weight_preset_revision(kind,preset_id,revision,weight_kg) VALUES(?,?,?,?)",kind.equals("baskets")?"BASKET":"GROSS",entity,revision,weight);
                }
                case "categories"->{if(present)db.jdbc.update("UPDATE category SET name=?,active=? WHERE id=?",name,active,entity);else db.jdbc.update("INSERT INTO category(id,name,active) VALUES(?,?,?)",entity,name,active);}
                case "products"->{String category=Objects.toString(body.get("categoryId"),"");if(!db.exists("SELECT id FROM category WHERE id=?",category))throw new Problem(400,"请选择类别");if(present)db.jdbc.update("UPDATE product SET name=?,category_id=?,active=? WHERE id=?",name,category,active,entity);else db.jdbc.update("INSERT INTO product(id,name,category_id,active) VALUES(?,?,?,?)",entity,name,category,active);}
                case "users"->{String role=Objects.toString(body.get("role"),"RECORDER"),username=Objects.toString(body.get("username"),"");if(!Set.of("ADMIN","RECORDER").contains(role)||!username.matches("[a-zA-Z0-9_-]{3,40}"))throw new Problem(400,"账号或角色无效");
                    if(entity.equals(a.id())&&(!active||!role.equals("ADMIN")))throw new Problem(400,"不能停用自己或移除自己的管理权限");
                    String password=Objects.toString(body.get("password"),"");if((!present||!password.isBlank())&&(password.length()<8||password.length()>72))throw new Problem(400,"密码应为 8 至 72 位");
                    if(present){db.jdbc.update("UPDATE farm_user SET username=?,display_name=?,role=?,active=? WHERE id=?",username,name,role,active,entity);if(!password.isBlank())db.jdbc.update("UPDATE farm_user SET password_hash=? WHERE id=?",new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(password),entity);}else db.jdbc.update("INSERT INTO farm_user(id,username,display_name,role,password_hash,active) VALUES(?,?,?,?,?,?)",entity,username,name,role,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(password),active);}
            }
            var after=db.one("SELECT "+(kind.equals("users")?"id,username,display_name,role,active":"*")+" FROM "+table+" WHERE id=?",entity);db.audit(a,"SAVE_"+kind.toUpperCase(),entity,before,after,"");return after;
        });
    }
    public Object publish(String product,LocalDate date,Rule rule,String reason,Actor actor){return publish(product,date,rule,reason,"FROM_DATE",actor);}
    public Object publish(String product,LocalDate date,Rule rule,String reason,String scope,Actor actor){
        pricing.validate(rule);if(reason==null||reason.isBlank()||reason.length()>500)throw new Problem(400,"请填写规则调整原因");
        if(!Set.of("MONTH","FROM_DATE").contains(scope))throw new Problem(400,"请选择规则适用范围");
        LocalDate start=scope.equals("MONTH")?date.withDayOfMonth(1):date;
        LocalDate end=scope.equals("MONTH")?YearMonth.from(date).atEndOfMonth():null;
        return db.tx.execute(status->{
            db.one("SELECT * FROM product WHERE id=? FOR UPDATE",product);
            // Lock every affected existing period, in chronological order, before repricing or settlement can read it.
            var months=db.rows("SELECT month_key FROM ledger_month WHERE month_key>=? ORDER BY month_key",start.toString().substring(0,7));
            for(var m:months)db.lockMonth(str(m,"month_key"));
            var affected=end==null?db.rows("SELECT * FROM harvest_record WHERE product_id=? AND work_date>=? AND deleted=FALSE",product,start):db.rows("SELECT * FROM harvest_record WHERE product_id=? AND work_date>=? AND work_date<=? AND deleted=FALSE",product,start,end);
            for(var record:affected){
                Inputs inputs=db.json.read(str(record,"inputs_json"),Inputs.class);
                Quote next;
                try{next=pricing.calculate(rule,inputs);}catch(Problem e){throw new Problem(400,"新规则不兼容已有记录（"+record.get("work_date")+"）："+e.getMessage()+"。请保持录入项兼容，或选择尚无记录的未来生效日期");}
                if(scope.equals("MONTH")){
                    Quote previous=pricing.calculate(rule(product,LocalDate.parse(str(record,"work_date"))),inputs);
                    if(next.amount().compareTo(previous.amount())<0)throw new Problem(400,"整月追溯调整不能降低已完成劳动的工资；降价请选择自指定日期起生效");
                }
            }
            String ruleId=id();db.jdbc.update("INSERT INTO price_rule(id,product_id,effective_date,end_date,rule_json,created_by,change_reason) VALUES(?,?,?,?,?,?,?)",ruleId,product,start,end,db.json.write(rule),actor.id(),reason);db.audit(actor,"PUBLISH_RULE",product,null,Map.of("id",ruleId,"effectiveDate",start,"scope",scope,"rule",rule),reason);return Map.of("id",ruleId);
        });
    }
}
