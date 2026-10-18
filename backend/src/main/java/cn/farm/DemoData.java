package cn.farm;

import static cn.farm.Domain.*;
import static cn.farm.Store.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Component
public class DemoData implements ApplicationRunner {
    final Store db;final boolean enabled;
    public DemoData(Store db,@Value("${farm.demo}") boolean enabled){this.db=db;this.enabled=enabled;}
    @Override public void run(ApplicationArguments args){if(!enabled||db.exists("SELECT id FROM farm_user"))return;
        db.tx.executeWithoutResult(status->{
            var encoder=new BCryptPasswordEncoder();String admin=id(),recorder=id(),other=id();
            db.jdbc.update("INSERT INTO farm_user(id,username,display_name,password_hash,role) VALUES(?,?,?,?,?)",admin,"admin","农场老板",encoder.encode("Farm@2026"),"ADMIN");
            db.jdbc.update("INSERT INTO farm_user(id,username,display_name,password_hash,role) VALUES(?,?,?,?,?)",recorder,"recorder","记录员小林",encoder.encode("Farm@2026"),"RECORDER");
            db.jdbc.update("INSERT INTO farm_user(id,username,display_name,password_hash,role) VALUES(?,?,?,?,?)",other,"recorder2","记录员小陈",encoder.encode("Farm@2026"),"RECORDER");
            String fruit=id(),leaf=id();db.jdbc.update("INSERT INTO category(id,name) VALUES(?,?)",fruit,"瓜果类");db.jdbc.update("INSERT INTO category(id,name) VALUES(?,?)",leaf,"叶菜类");
            List<String> workerIds=new ArrayList<>();String[] names={"张建国","李秀兰","王海生","陈春梅","刘志强","赵玉芳"};for(int i=0;i<names.length;i++){String w=id();workerIds.add(w);db.jdbc.update("INSERT INTO worker(id,code,name,phone) VALUES(?,?,?,?)",w,"W"+String.format("%03d",i+1),names[i],"138000000"+String.format("%02d",i+1));}
            List<String> products=new ArrayList<>();String[] pn={"番茄","黄瓜","豇豆","小白菜"};LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
            for(int i=0;i<pn.length;i++){String p=id();products.add(p);db.jdbc.update("INSERT INTO product(id,category_id,name) VALUES(?,?,?)",p,i==3?leaf:fruit,pn[i]);Rule rule=new Rule("SEGMENTS",List.of(new Stage("main",i==0?"采摘筐数":"采摘重量",i==0?"PIECE":"WEIGHT",new BigDecimal(i==0?"3":i==1?"0.8":i==2?"1.2":"0.5"),null,null,null,null)));db.jdbc.update("INSERT INTO price_rule(id,product_id,effective_date,rule_json,created_by,change_reason) VALUES(?,?,?,?,?,?)",id(),p,today.withDayOfMonth(1),db.json.write(rule),admin,"演示初始计费规则");}
            for(int day=0;day<Math.min(today.getDayOfMonth(),10);day++)for(int w=0;w<workerIds.size();w++){
                int product=(w+day)%products.size();Inputs input=new Inputs(Map.of("main",new BigDecimal(product==0?"8.5":String.valueOf(24+w*3+day))),null,"kg");String rid=id();db.jdbc.update("INSERT INTO harvest_record(id,worker_id,work_date,period,product_id,inputs_json,note,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?)",rid,workerIds.get(w),today.minusDays(day),w%2==0?"AM":"PM",products.get(product),db.json.write(input),"演示记录",recorder,recorder);}
            db.audit(new Actor(admin,"admin","农场老板","ADMIN"),"DEMO_INIT","demo",null,Map.of("workers",6,"products",4),"初始化演示资料");
        });
    }
}
